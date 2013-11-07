/*
 * Provider.java
 * Created: 29/09/2013
 *
 * Copyright 2013 Systemic Pty Ltd
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

package sif3.common.provider;

import java.util.List;

import sif3.common.conversion.MarshalFactory;
import sif3.common.conversion.ModelObjectInfo;
import sif3.common.conversion.UnmarshalFactory;
import sif3.common.exception.PersistenceException;
import sif3.common.exception.UnsupportedQueryException;
import sif3.common.model.PagingInfo;
import sif3.common.model.SIFContext;
import sif3.common.model.SIFZone;
import sif3.common.ws.OperationStatus;

/**
 * This class defines the methods a provider must implement to fit with this framework. The interface is independent from the 
 * Data Model and underlying infrastructure components. It defines the core function SIF3 specifies for a provider.<br/><br/>
 * 
 * Note:<br/>
 * Because this framework allows to be run under Java 6 some of the types in various methods use "Object" instead of the  template 
 * notation. This is because Java 6 doesn't allow a 'new T()' and therefore the interface avoids the template notation to not break 
 * the implementation where a constructor for an Object might be required. This may change in future versions of the framework.
 * 
 * @author Joerg Huber
 */
public interface Provider
{
	/**
	 * This method returns the object with the given resourceID in the location defined by the zone and context. If the object with the
	 * given resourceID doesn't exist then null is returned.
	 * 
	 * @param resourceID The resourceID of the object that shall be returned.
	 * @param zone Can be Null (default Zone)
	 * @param context Can be Null (default Context)
	 * 
	 * @return The entity for this resourceID. Null if entity with given resourceID does not exist.
	 * 
	 * @throws IllegalArgumentException One of the parameters is invalid.
	 * @throws PersistenceException Persistance Store could not be accessed successfuly. An error log entry is performed and the 
	 *                              message of the exceptions holds some info.
	 */
	public Object retrievByPrimaryKey(String resourceID, SIFZone zone, SIFContext context) throws IllegalArgumentException, PersistenceException;

	/**
	 * This method creates the given object with the data provided in the given zone and context. If the object cannot be created then either
	 * an exception is raised or null is returned.
	 * 
	 * @param data The data of the actual Object to be created. It may or may not hold the resourceID and the provider may or may not accept it.
	 *             It is up to the implementation to make that decision. The final resourceID is returned as part of the returned object.
	 * @param zone The Zone from which the request is being issued. Can be Null (default Zone)
	 * @param context The Context for which the object shall be created. Can be Null (default Zone)
	 * 
	 * @return The object that is created. It may hold additional data than the one provided.
	 * 
   * @throws IllegalArgumentException One of the parameters is invalid.
   * @throws PersistenceException Persistance Store could not be accessed successfuly. An error log entry is performed and the 
   *                              message of the exceptions holds some info.
	 */
	public Object createSingle(Object data, SIFZone zone, SIFContext context) throws IllegalArgumentException, PersistenceException;

	/**
   * This method updates the object given by its resourceID in the given zone for the given context.
   * 
   * @param data The actual object (i.e. Student). It holds the values of the object that need to be updated. It can either hold the
   *             full object or only parts of the object that needs updating. 
   * @param resourceID The Id of the object to be updated.
   * @param zone The Zone from which the request is being issued. Can be Null (default Zone)
   * @param context The Context for which the object shall be updated. Can be Null (default Zone)
	 * 
	 * @return TRUE: Entity is updated. FALSE: Entity does not exist.
	 * 
   * @throws IllegalArgumentException One of the parameters is invalid.
   * @throws PersistenceException Persistance Store could not be accessed successfuly. An error log entry is performed and the 
   *                              message of the exceptions holds some info.
	 */
	public boolean updateSingle(Object data, String resourceID, SIFZone zone, SIFContext context) throws IllegalArgumentException, PersistenceException;

	/**
   * Removed the object with the given resourceId in the given zone for the given context.
   * 
   * @param resourceID The Id of the object to be removed.
   * @param zone The Zone from which the request is being issued. Can be Null (default Zone)
   * @param context The Context for which the object shall be removed. Can be Null (default Zone)
	 * 
	 * @return TRUE: Entity is removed. FALSE: Entity does not exist.
	 * 
   * @throws IllegalArgumentException One of the parameters is invalid.
   * @throws PersistenceException Persistance Store could not be accessed successfuly. An error log entry is performed and the 
   *                              message of the exceptions holds some info.
	 */
	public boolean deleteSingle(String resourceID, SIFZone zone, SIFContext context) throws IllegalArgumentException, PersistenceException;

	/*---------------------*/
	/*-- Bulk Operations --*/
	/*---------------------*/

	/**
   * This method is used to retrieve any number of objects. This is achieved in terms of 'paging' through the list of objects. The consumer
   * is expected to provide paging information to tell the provider which objects in the list shall be returned. The first page has
   * the number 0.
   * 
   * @param zone The Zone from which the request is being issued. Can be Null (default Zone)
   * @param context The Context for which the objects shall be returned. Can be Null (default Zone)
   * @param pagingInfo Page information to determine which results to return. Null = Return all (NOT RECOMMENDED!).
   * 
   * @throws UnsupportedQueryException The query provided with this request is not supported (NOT YET IMPLEMENTED FUNCTIONALITY)
   * @throws PersistenceException Persistence Store could not be accessed successfully. An error log entry is performed and the 
   *                              message of the exceptions holds some info.
	 */
	public Object retrieve(SIFZone zone, SIFContext context, PagingInfo pagingInfo) throws PersistenceException, UnsupportedQueryException;
	
	/**
   * This method will create many objects in one call. The 'data' parameter is a collection-style object that is defined in the data
   * model (i.e. StudentPersonals which is a collection of StudentPersonal).
   * 
   * @param data The 'collection' object. Each object in that collection will be created.
   * @param zone The Zone from which the request is being issued. Can be Null (default Zone)
   * @param context The Context for which the objects shall be created. Can be Null (default Zone)
	 * 
	 * @return List with Status and IDs for each created object, or Status and Error for each object. Null if there was an unknown error. 
	 * 
   * @throws IllegalArgumentException One of the parameters is invalid.
   * @throws PersistenceException Persistance Store could not be accessed successfuly. An error log entry is performed and the 
   *                              message of the exceptions holds some info.
	 */
	public List<OperationStatus> createMany(Object data, SIFZone zone, SIFContext context) throws IllegalArgumentException, PersistenceException;

	/**
   * This method will update many objects in one call. The 'data' parameter is a collection-style object that is defined in the data
   * model (i.e. StudentPersonals which is a collection of StudentPersonal). Each object in the collection can either hold the
   * full object or only parts of the object that needs updating.
   * 
   * @param data The 'collection' object. Each object in that collection will be updated.
   * @param zone The Zone from which the request is being issued. Can be Null (default Zone)
   * @param context The Context for which the objects shall be updated. Can be Null (default Zone)
	 * 
   * @return List with Status and IDs for each updated object, or Status and Error for each object. Null if there was an unknown error. 
	 * 
   * @throws IllegalArgumentException One of the parameters is invalid.
   * @throws PersistenceException Persistance Store could not be accessed successfuly. An error log entry is performed and the 
   *                              message of the exceptions holds some info.
	 */
	public List<OperationStatus> updateMany(Object data, SIFZone zone, SIFContext context) throws IllegalArgumentException, PersistenceException;

	/**
   * This method removes all objects in the resourceIDs list in one hit.
   * 
   * @param resourceIDs A list of resourceId for the objects to be removed.
   * @param zone The Zone from which the request is being issued. Can be Null (default Zone)
   * @param context The Context for which the objects shall be removed. Can be Null (default Zone)
	 * 
   * @return List with Status and IDs for each removed object, or Status and Error for each object. Null if there was an unknown error. 
	 * 
   * @throws IllegalArgumentException One of the parameters is invalid.
   * @throws PersistenceException Persistance Store could not be accessed successfuly. An error log entry is performed and the 
   *                              message of the exceptions holds some info.
	 */
	public List<OperationStatus> deleteMany(List<String> resourceIDs, SIFZone zone, SIFContext context) throws IllegalArgumentException, PersistenceException;

	/*-------------------------------*/
	/*-- Other required Operations --*/
	/*-------------------------------*/	
	
	/**
	 * Returns a marshaller applicable for this data model supported with this provider.
	 */
	public MarshalFactory getMarshaller();
	
  /**
   * Returns an unmarshaller applicable for this data model supported with this provider.
   */
	public UnmarshalFactory getUnmarshaller();
	
	/**
	 * Returns the information for the 'single object'. The returned object holds the name of a 'single object' (i.e StudentPersonal) and
	 * the physical class this maps to for the data model this provider supports.
	 * 
	 * @return See Desc.
	 */
	public ModelObjectInfo getSingleObjectClassInfo();
	
  /**
   * Returns the information for the 'collection-style object'. The returned object holds the name of a 'collection-style object' 
   * (i.e StudentPersonal) and the physical class this maps to for the data model this provider supports.
   * 
   * @return See Desc.
   */
	public ModelObjectInfo getMultiObjectClassInfo();


	/**
	 * This method is called when a provider shuts down. Can be used to clean-up internally held resources etc. 
   */
	public void finalise();
}