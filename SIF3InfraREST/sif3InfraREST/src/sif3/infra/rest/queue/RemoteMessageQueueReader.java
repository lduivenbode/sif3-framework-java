/*
 * RemoteMessageQueueReader.java
 * Created: 08/05/2014
 *
 * Copyright 2014 Systemic Pty Ltd
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License 
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package sif3.infra.rest.queue;

import java.net.URI;

import javax.ws.rs.core.Response.Status;

import org.apache.log4j.Logger;

import sif3.common.CommonConstants;
import sif3.common.exception.ServiceInvokationException;
import sif3.common.header.HeaderValues.EventAction;
import sif3.common.header.HeaderValues.MessageType;
import sif3.common.header.HeaderValues.UpdateType;
import sif3.common.header.ResponseHeaderConstants;
import sif3.common.model.EventMetadata;
import sif3.common.model.SIFContext;
import sif3.common.model.SIFZone;
import sif3.common.persist.model.SIF3Session;
import sif3.common.ws.Response;
import sif3.infra.common.env.types.ConsumerEnvironment;
import sif3.infra.rest.client.MessageClient;
import sif3.infra.rest.queue.types.EventInfo;
import sif3.infra.rest.queue.types.QueueListenerInfo;
import au.com.systemic.framework.utils.StringUtils;

/**
 * This class is the actual reader on the remote SIF Queue. It deals with all the logic that applies to SIF Message Queues and how
 * efficient reading from these queues can be achieved. It implements the full queue reading process as defined in the SIF3 Specification.
 * This class can be multi-threaded as defined by the SIF3 Spec. Additionally this implementation will not fully process the
 * received message rather it will push it to a local queue where actual consumer classes will be listening on. This allows the
 * actual consumers to be either single threads or multi-threads with out this class needing the knowledge how consumer are
 * implemented.<br/><br/>
 * 
 * Note: It doesn't implement the full queue connector, just the "getMessage" part of the queue connector.
 * 
 * @author Joerg Huber
 *
 */
public class RemoteMessageQueueReader implements Runnable
{
	protected final Logger logger = Logger.getLogger(getClass());

	private QueueListenerInfo queueListenerInfo = null;
	private ConsumerEnvironment consumerEnvInfo = null;
	private SIF3Session sif3Session = null;
	private String readerID = null;
	private String lastMsgeID = null;
	private int waitTime = 0; // milliseconds

	private MessageClient client = null;

	/**
	 * Constructs a RemoteMessageQueueReader for the queue identified through the queueListenerInfo parameter for the given session and consumer 
	 * configuration.
	 * 
	 * @param queueListenerInfo
	 *            Holds all the information for this reader to identify what SIF queue to read from and where to distribute messages to.
	 * @param consumerEnvInfo
	 *            Consumer configuration. It is required to determine timeout delays and other user configured properties for message 
	 *            consumption from a queue.
	 * @param sif3Session
	 *            The session for which this queue reader is for. This is required to connect to the message URI from the SIF queue.
	 * @param readerID
	 *            A string identifying the ID of this reader. Since it is expected that readers are running in multiple threads each 
	 *            reader should have its own id to identify it for logging purpose.
	 */
	public RemoteMessageQueueReader(QueueListenerInfo queueListenerInfo, ConsumerEnvironment consumerEnvInfo, SIF3Session sif3Session, String readerID) throws ServiceInvokationException
	{
		super();
		try
		{
			this.queueListenerInfo = queueListenerInfo;
			this.consumerEnvInfo = consumerEnvInfo;
			this.sif3Session = sif3Session;
			this.readerID = readerID;

			// Get the wait time between get message calls once a no message is returned. Timeout is  the max from what the queue indicates
			// and what the configuration states.
			waitTime = Math.max(longToInt(getQueueListenerInfo().getQueue().getWaitTime()), getConsumerEnvInfo().getPollFrequency()) * CommonConstants.MILISEC;

			// Initialise message client. Only needs to be done once, so we do this here.
			client = new MessageClient(new URI(queueListenerInfo.getQueue().getMessageURI()), getConsumerEnvInfo(), getSif3Session());
		}
		catch (Exception ex)
		{
			logger.error("Failed do create a QueueReader: " + ex.getMessage(), ex);
			client = null;
			throw new ServiceInvokationException(ex);
		}
	}

	public void shutdown()
	{
		// nothing to do at the moment
		logger.debug("Shutdown Message Reader wit ID = " + getReaderID() + " for queue = " + getQueueListenerInfo().getQueue().getName());
	}
	
	/* (non-Javadoc)
     * @see java.lang.Runnable#run()
     */
    @Override
    public void run()
    {
    	logger.debug("Message Queue Reader "+getReaderID()+" starts reading messages...");
    	startReading();
    }

	/*---------------------*/
	/*-- Private Methods --*/
	/*---------------------*/
    /*
     * This is the actual core methods. It connect to the SIF Message queue and starts reading messages from it. 
     */
	private void startReading()
	{
		if (client != null) // indicating all good
		{
			while (true)
			{
				try
				{
					Response response = getClient().getMessage(getLastMsgeID(), getReaderID());
					setLastMsgeID(response); // ensure that the next loop iteration we will remove the current message

					if (isNoMessageResponse(response)) // no more messages available
					{
						waitBeforeGetNext(); // Wait until query the queue next time.
					}
					else if (isErrorResponse(response))
					{
						waitBeforeGetNext(); // Wait until query the queue next time.
					}
					else
					{
						logger.debug("Message Reader '" + getReaderID() + "' (ThreadID:"+Thread.currentThread().getId()+") has receive a message from queue: " + getQueueListenerInfo().getQueue().getName() + ". Message ID = " + getLastMsgeID());
						processMessage(response);
					}
				}
				catch (ServiceInvokationException ex)
				{
					// Error should already have been logged. Just wait and try again
					waitBeforeGetNext();	  
				}
			}
		}
	}

	private boolean isNoMessageResponse(Response response)
	{
		if (response.getStatus() == Status.NO_CONTENT.getStatusCode())
		{
			// Remove last msgID since this is just a normal status message
			setMsgIDToNull();
			return true;
		}
		return false;
	}

	/*
	 * CHecks if an error is returned. If so it will log it, remove the lastMsg id and return true. Otherwise flase is returned.
	 */
	private boolean isErrorResponse(Response response)
	{
		if (response.hasError()) // An error message was on the queue. Just report it for the time being
		{
			logger.error("Error received:\n" + response.getError());
			
			// We must also set the lastMsgID to null!
			setMsgIDToNull();
			return true;
		}
		return false;
	}

	private void waitBeforeGetNext()
	{
		logger.debug("\n==========================\n"+getReaderID()+ " will wait for "+getWaitTime()/CommonConstants.MILISEC+" seconds before attempting to get next message."+"\n==========================");
		try
		{
			Object semaphore = new Object();
			synchronized (semaphore)
			{
				semaphore.wait(getWaitTime());
			}
		}
		catch (Exception ex)
		{
			logger.error("Blocking wait in Message Reader '" + getReaderID() + "' for queue: " + getQueueListenerInfo().getQueue().getName() + " interrupted: " + ex.getMessage(), ex);
		}
	}

	/*
	 * This is the main method that deals with processing a message that has been received.
	 */
	private void processMessage(Response response)
	{
		String responseType = response.getHdrProperties().getHeaderProperty(ResponseHeaderConstants.HDR_MESSAGE_TYPE);
		if (StringUtils.isEmpty(responseType)) // not good!
		{
			logger.error("Message received with unknown message type (null or empty). Cannot process response:\n" + response);
			return; // attempt to process next message
		}

		// All good. Check what response type we are dealing with
		MessageType messageType = MessageType.EVENT;
		try
		{
			messageType = MessageType.valueOf(responseType);
		}
		catch (Exception ex)
		{
			logger.error("Message received with unknown message type: '" + responseType + "'. Cannot process response:\n" + response);
			return; // attempt to process next message
		}
		if (messageType == MessageType.EVENT)
		{
			processEvent(response);
		}
		else if (messageType == MessageType.RESPONSE)
		{
			// TODO: JH - Implement Delayed Responses.
			logger.info("Delayed Responses not yet supported. Response ignored.:\n" + response);
			return; // attempt to process next message
		}
		else
		// we should not get there because ERROR is the only one left and that has already been handled.
		{
			logger.info("Message of type ERROR received but no error details are given. Cannot process response:\n" + response);
			return; // attempt to process next message
		}
	}

	private void processEvent(Response response)
	{
		try
		{
			if (logger.isDebugEnabled())
			{
				//logger.debug("Header Properties for Event Response:\n"+response.getHdrProperties());
				logger.debug("Event Message Received:\n"+response);
			}
			SIFZone zone = getZone(response);
			SIFContext context = getContext(response);
			String serviceName = response.getHdrProperties().getHeaderProperty(ResponseHeaderConstants.HDR_SERVICE_NAME);
			String serviceType = response.getHdrProperties().getHeaderProperty(ResponseHeaderConstants.HDR_SERVICE_TYPE);

			// Is there a subscription for this event?
			LocalConsumerQueue localQueue = queueListenerInfo.getLocalConsumerQueue(zone.getId(), context.getId(), serviceName, serviceType);
			if (localQueue == null) // There is an event for which there is no registered consumer.
			{
				logger.info("Received an event for which there is no consumer registered. Discard the following Event:\n" + response);
			}
			else // Create event object and send it to eventConsumer
			{
				String eventPayload = (String)response.getDataObject();
				EventAction eventAction = getEventAction(response);
				UpdateType updateType = null;
				if ((eventAction != null) && (eventAction == EventAction.UPDATE))
				{
					updateType = getUpdateType(response);
				}
				
				EventMetadata metadata = new EventMetadata(response.getHdrProperties());
				
				// Set the generator ID in its specific property for easy access.
				metadata.setGeneratorID(response.getHdrProperties().getHeaderProperty(ResponseHeaderConstants.HDR_GENERATOR_ID));
				
				//TODO: JH - Do we need applicationKey and authenticatedUser HTTP header here?				
				
				EventInfo eventInfo = new EventInfo(eventPayload, response.getMediaType(), eventAction, updateType, zone, context, metadata, getReaderID());
				logger.debug(getReaderID()+": Attempts to push Event to local queue...");
				localQueue.blockingPush(eventInfo);
				logger.debug(getReaderID()+": Event successfully pushed to local queue");
			}
		}
		catch (Exception ex)
		{
			logger.error("Error occured during the processing of a message from the queue: " + getQueueListenerInfo().getQueue().getName() + ". See previous error log entries for details: " + ex.getMessage(), ex);
		}
	}

	private EventAction getEventAction(Response response)
	{
		String eventAction = response.getHdrProperties().getHeaderProperty(ResponseHeaderConstants.HDR_EVENT_ACTION);
		if (StringUtils.notEmpty(eventAction))
		{
			try
			{
				return EventAction.valueOf(eventAction);
			}
			catch (Exception ex)
			{
				logger.error("Received an event with an invalid event action: '" + eventAction + "':\n" + response);
				return null;
			}
		}
		else
		{
			logger.error("Received an event with no event action set in the response header:\n" + response);
			return null;
		}
	}

	private UpdateType getUpdateType(Response response)
	{
		String updateType = response.getHdrProperties().getHeaderProperty(ResponseHeaderConstants.HDR_UPDATE_TYPE);
		if (StringUtils.notEmpty(updateType))
		{
			try
			{
				return UpdateType.valueOf(updateType);
			}
			catch (Exception ex)
			{
				logger.error("Received an update event with an invalid update type: '" + updateType + "':\n" + response);
				return null;
			}
		}
		else
		{
			logger.error("Received an update event with no update type set in the response header:\n" + response);
			return null;
		}
	}

	private SIFZone getZone(Response response)
	{
		String zoneID = response.getHdrProperties().getHeaderProperty(ResponseHeaderConstants.HDR_ZONE_ID);
		if (StringUtils.isEmpty(zoneID)) // use default zone
		{
			return getSif3Session().getDefaultZone();
		}
		else
		{
			if (getSif3Session().getDefaultZone().getId().equals(zoneID))
			{
				return getSif3Session().getDefaultZone();
			}
			else
			{
				return new SIFZone(zoneID, false);
			}
		}
	}

	private SIFContext getContext(Response response)
	{
		String contextID = response.getHdrProperties().getHeaderProperty(ResponseHeaderConstants.HDR_CONTEXT_ID);
		if (StringUtils.isEmpty(contextID)) // use default zone
		{
			return CommonConstants.DEFAULT_CONTEXT;
		}
		else
		{
			if (CommonConstants.DEFAULT_CONTEXT_NAME.equals(contextID))
			{
				return CommonConstants.DEFAULT_CONTEXT;
			}
			else
			{
				return new SIFContext(contextID, false);
			}
		}
	}

	private int longToInt(Long value)
	{
		return (value == null) ? 0 : value.intValue();
	}

	private QueueListenerInfo getQueueListenerInfo()
	{
		return queueListenerInfo;
	}

	private ConsumerEnvironment getConsumerEnvInfo()
	{
		return consumerEnvInfo;
	}

	private SIF3Session getSif3Session()
	{
		return sif3Session;
	}

	private MessageClient getClient()
	{
		return client;
	}

	private String getReaderID()
	{
		return readerID;
	}

	private String getLastMsgeID()
	{
		return lastMsgeID;
	}

	private void setLastMsgeID(Response response)
	{
		this.lastMsgeID = response.getHdrProperties().getHeaderProperty(ResponseHeaderConstants.HDR_MESSAGE_ID);
	}
	
	private void setMsgIDToNull()
	{
		this.lastMsgeID =  null;
	}

	private int getWaitTime()
	{
		return waitTime;
	}

}
