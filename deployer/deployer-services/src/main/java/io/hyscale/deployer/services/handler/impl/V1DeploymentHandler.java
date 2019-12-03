/**
 * Copyright 2019 Pramati Prism, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.hyscale.deployer.services.handler.impl;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.google.gson.JsonSyntaxException;

import io.hyscale.commons.exception.HyscaleException;
import io.hyscale.commons.logger.ActivityContext;
import io.hyscale.commons.logger.WorkflowLogger;
import io.hyscale.commons.models.AnnotationKey;
import io.hyscale.commons.models.Status;
import io.hyscale.deployer.core.model.ResourceKind;
import io.hyscale.deployer.core.model.ResourceOperation;
import io.hyscale.deployer.services.exception.DeployerErrorCodes;
import io.hyscale.deployer.services.handler.ResourceLifeCycleHandler;
import io.hyscale.deployer.services.model.DeployerActivity;
import io.hyscale.deployer.services.model.ResourceStatus;
import io.hyscale.deployer.services.util.ExceptionHelper;
import io.hyscale.deployer.services.util.K8sResourcePatchUtil;
import io.hyscale.deployer.services.util.KubernetesApiProvider;
import io.hyscale.deployer.services.util.KubernetesResourceUtil;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.AppsV1beta2Api;
import io.kubernetes.client.models.V1DeleteOptions;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1beta2Deployment;
import io.kubernetes.client.models.V1beta2DeploymentList;
import io.kubernetes.client.models.V1beta2DeploymentStatus;

public class V1DeploymentHandler implements ResourceLifeCycleHandler<V1beta2Deployment> {

	private static final Logger LOGGER = LoggerFactory.getLogger(V1DeploymentHandler.class);

	@Override
	public V1beta2Deployment create(ApiClient apiClient, V1beta2Deployment resource, String namespace)
			throws HyscaleException {
		if (resource == null) {
			LOGGER.debug("Cannot create null Deployment");
			return resource;
		}
		WorkflowLogger.startActivity(DeployerActivity.DEPLOYING_DEPLOYMENT);
		V1ObjectMeta metadata = resource.getMetadata();
		try {
		    KubernetesResourceUtil.isResourceValid(apiClient, getKind(), metadata);
		} catch(HyscaleException e) {
		    WorkflowLogger.endActivity(Status.FAILED);
            throw e;
		}
		AppsV1beta2Api appsV1beta2Api = KubernetesApiProvider.getAppsV1beta2Api(apiClient);
		V1beta2Deployment v1beta2Deployment = null;
		try {
			resource.getMetadata().putAnnotationsItem(
					AnnotationKey.K8S_HYSCALE_LAST_APPLIED_CONFIGURATION.getAnnotation(), gson.toJson(resource));
			v1beta2Deployment = appsV1beta2Api.createNamespacedDeployment(namespace, resource, null, TRUE, null);
		} catch (ApiException e) {
			HyscaleException ex = new HyscaleException(e, DeployerErrorCodes.FAILED_TO_CREATE_RESOURCE,
					ExceptionHelper.getExceptionArgs(getKind(), e, ResourceOperation.CREATE));
			LOGGER.error("Error while creating Deployment {} in namespace {}, error {}", v1beta2Deployment, namespace,
					ex.toString());
			WorkflowLogger.endActivity(Status.FAILED);
			throw ex;
		}
		WorkflowLogger.endActivity(Status.DONE);
		return v1beta2Deployment;
	}

	@Override
	public boolean update(ApiClient apiClient, V1beta2Deployment resource, String namespace) throws HyscaleException {
		if(resource==null){
			LOGGER.debug("Cannot update null Deployment");
			return false;
		}
		V1ObjectMeta metadata = resource.getMetadata();
		try {
            KubernetesResourceUtil.isResourceValid(apiClient, getKind(), metadata);
        } catch(HyscaleException e) {
            WorkflowLogger.startActivity(DeployerActivity.DEPLOYING_DEPLOYMENT);
            WorkflowLogger.endActivity(Status.FAILED);
            throw e;
        }
		AppsV1beta2Api appsV1beta2Api = KubernetesApiProvider.getAppsV1beta2Api(apiClient);
        String name = metadata.getName();
		V1beta2Deployment existingDeployment = null;
		try {
			existingDeployment = get(apiClient, name, namespace);
		} catch (HyscaleException ex) {
			LOGGER.debug("Error while getting Deployment {} in namespace {} for Update, creating new", name, namespace);
			V1beta2Deployment deployment = create(apiClient, resource, namespace);
			return deployment != null ? true : false;
		}
		WorkflowLogger.startActivity(DeployerActivity.DEPLOYING_DEPLOYMENT);
		try {
			String resourceVersion = existingDeployment.getMetadata().getResourceVersion();
			metadata.setResourceVersion(resourceVersion);
			appsV1beta2Api.replaceNamespacedDeployment(name, namespace, existingDeployment, TRUE, null);
		} catch (ApiException e) {
			HyscaleException ex = new HyscaleException(e, DeployerErrorCodes.FAILED_TO_UPDATE_RESOURCE,
					ExceptionHelper.getExceptionArgs(getKind(), e, ResourceOperation.UPDATE));
			LOGGER.error("Error while updating Deployment {} in namespace {}, error {}", name, namespace,
					ex.toString());
			WorkflowLogger.endActivity(Status.FAILED);
			throw ex;
		}
		WorkflowLogger.endActivity(Status.DONE);
		return true;
	}

	@Override
	public V1beta2Deployment get(ApiClient apiClient, String name, String namespace) throws HyscaleException {
	    KubernetesResourceUtil.isResourceValid(apiClient, getKind(), name);
		AppsV1beta2Api appsV1beta2Api = KubernetesApiProvider.getAppsV1beta2Api(apiClient);
		V1beta2Deployment v1beta2Deployment = null;
		try {
			v1beta2Deployment = appsV1beta2Api.readNamespacedDeployment(name, namespace, TRUE, false, false);
		} catch (ApiException e) {
			HyscaleException ex = ExceptionHelper.buildGetException(getKind(), e, ResourceOperation.GET);
			LOGGER.error("Error while fetching Deployment {} in namespace {}, error {} ", name, namespace,
					ex.toString());
			throw ex;
		}
		return v1beta2Deployment;
	}

	@Override
	public List<V1beta2Deployment> getBySelector(ApiClient apiClient, String selector, boolean label, String namespace)
			throws HyscaleException {
	    if (apiClient == null) {
	        throw new HyscaleException(DeployerErrorCodes.API_CLIENT_REQUIRED);
	    }
		AppsV1beta2Api appsV1beta2Api = KubernetesApiProvider.getAppsV1beta2Api(apiClient);
		List<V1beta2Deployment> v1beta2Deployments = null;
		try {
			String labelSelector = label ? selector : null;
			String fieldSelector = label ? null : selector;

			V1beta2DeploymentList v1beta2DeploymentList = appsV1beta2Api.listNamespacedDeployment(namespace, null, TRUE,
					null, fieldSelector, labelSelector, null, null, null, null);

			v1beta2Deployments = v1beta2DeploymentList != null ? v1beta2DeploymentList.getItems() : null;
		} catch (ApiException e) {
			HyscaleException ex = ExceptionHelper.buildGetException(getKind(), e, ResourceOperation.GET_BY_SELECTOR);
			LOGGER.error("Error while listing Deployments in namespace {}, with selectors {}, error {} ", namespace,
					selector, ex.toString());
			throw ex;
		}
		return v1beta2Deployments;
	}

	@Override
	public boolean patch(ApiClient apiClient, String name, String namespace, V1beta2Deployment target)
			throws HyscaleException {
		if (target == null) {
			LOGGER.debug("Cannot patch null Deployment");
			return false;
		}
		V1ObjectMeta metadata = target.getMetadata();
		try {
		    KubernetesResourceUtil.isResourceValid(apiClient, getKind(), metadata, name);
		} catch (HyscaleException e) {
		    WorkflowLogger.startActivity(DeployerActivity.DEPLOYING_DEPLOYMENT);
		    WorkflowLogger.endActivity(Status.FAILED);
		    throw e;
		}
		AppsV1beta2Api appsV1beta2Api = KubernetesApiProvider.getAppsV1beta2Api(apiClient);
        metadata.putAnnotationsItem(AnnotationKey.K8S_HYSCALE_LAST_APPLIED_CONFIGURATION.getAnnotation(),
				gson.toJson(target));
		V1beta2Deployment sourceDeployment = null;
		try {
			sourceDeployment = get(apiClient, name, namespace);
		} catch (HyscaleException e) {
			LOGGER.debug("Error while getting Deployment {} in namespace {} for Patch, creating new", name, namespace);
			V1beta2Deployment deployment = create(apiClient, target, namespace);
			return deployment != null ? true : false;
		}
		WorkflowLogger.startActivity(DeployerActivity.DEPLOYING_DEPLOYMENT);
		Object patchObject = null;
		String lastAppliedConfig = sourceDeployment.getMetadata().getAnnotations()
				.get(AnnotationKey.K8S_HYSCALE_LAST_APPLIED_CONFIGURATION.getAnnotation());
		try {
			patchObject = K8sResourcePatchUtil.getJsonPatch(gson.fromJson(lastAppliedConfig, V1beta2Deployment.class),
					target, V1beta2Deployment.class);
			appsV1beta2Api.patchNamespacedDeployment(name, namespace, patchObject, TRUE, null);
		} catch (HyscaleException e) {
			LOGGER.error("Error while creating patch for Deployment {}, source {}, target {}", name, sourceDeployment,
					target);
			WorkflowLogger.endActivity(Status.FAILED);
			throw e;
		} catch (ApiException e) {
			HyscaleException ex = new HyscaleException(e, DeployerErrorCodes.FAILED_TO_PATCH_RESOURCE,
					ExceptionHelper.getExceptionArgs(getKind(), e, ResourceOperation.PATCH));
			LOGGER.error("Error while patching Deployment {} in namespace {} , error {}", name, namespace,
					ex.toString());
			WorkflowLogger.endActivity(Status.FAILED);
			throw ex;
		}
		WorkflowLogger.endActivity(Status.DONE);
		return true;
	}

	@Override
	public boolean delete(ApiClient apiClient, String name, String namespace, boolean wait) throws HyscaleException {
	    ActivityContext activityContext = new ActivityContext(DeployerActivity.DELETING_DEPLOYMENT);
	    WorkflowLogger.startActivity(activityContext);
	    try {
	        KubernetesResourceUtil.isResourceValid(apiClient, getKind(), name);
	    } catch (HyscaleException e) {
	        WorkflowLogger.endActivity(activityContext, Status.FAILED);
	        throw e;
	    }
	    
		AppsV1beta2Api appsV1beta2Api = KubernetesApiProvider.getAppsV1beta2Api(apiClient);
		V1DeleteOptions deleteOptions = getDeleteOptions();
		deleteOptions.setApiVersion("apps/v1beta2");
		try {
			try {
			    appsV1beta2Api.deleteNamespacedDeployment(name, namespace, deleteOptions, TRUE, null, null, null, null);
			} catch (JsonSyntaxException e) {
			    // K8s end exception ignore
			}
			List<String> pendingDeployments = Lists.newArrayList();
			pendingDeployments.add(name);
			if (wait) {
				waitForResourceDeletion(apiClient, pendingDeployments, namespace, activityContext);
			}
		} catch (ApiException e) {
			if (e.getCode() == 404) {
				WorkflowLogger.endActivity(activityContext, Status.NOT_FOUND);
				return false;
			}
			HyscaleException ex = new HyscaleException(e, DeployerErrorCodes.FAILED_TO_DELETE_RESOURCE,
					ExceptionHelper.getExceptionArgs(getKind(), e, ResourceOperation.DELETE));
			LOGGER.error("Error while deleting Deployment {} in namespace {}, error {} ", name, namespace,
					ex.toString());
			WorkflowLogger.endActivity(activityContext, Status.FAILED);
			throw ex;
		}
		WorkflowLogger.endActivity(activityContext, Status.DONE);
		return true;
	}

	@Override
	public boolean deleteBySelector(ApiClient apiClient, String selector, boolean label, String namespace, boolean wait)
			throws HyscaleException {
		try {
			List<V1beta2Deployment> deploymentList = getBySelector(apiClient, selector, label, namespace);
			if (deploymentList == null || deploymentList.isEmpty()) {
				return false;
			}
			for (V1beta2Deployment deployment : deploymentList) {
				delete(apiClient, deployment.getMetadata().getName(), namespace, wait);
			}
		} catch (HyscaleException e) {
			if (DeployerErrorCodes.RESOURCE_NOT_FOUND.equals(e.getHyscaleErrorCode())) {
				LOGGER.error("Error while deleting deployment for selector {} in namespace {}, error {}", selector,
						namespace, e.toString());
				return false;
			}
			throw e;
		}
		return true;
	}

	@Override
	public String getKind() {
		return ResourceKind.DEPLOYMENT.getKind();
	}

	@Override
	public boolean cleanUp() {
		return true;
	}

	@Override
	public ResourceStatus status(V1beta2Deployment deployment) {
	    if (deployment == null || deployment.getStatus() == null) {
	        return ResourceStatus.FAILED;
	    }
		V1beta2DeploymentStatus deploymentStatus = deployment.getStatus();
		Integer desiredReplicas = deployment.getSpec().getReplicas();
		Integer statusReplicas = deploymentStatus.getReplicas();
		Integer updatedReplicas = deploymentStatus.getUpdatedReplicas();
		Integer availableReplicas = deploymentStatus.getAvailableReplicas();
		Integer readyReplicas = deploymentStatus.getReadyReplicas();
		if ((desiredReplicas == null || desiredReplicas == 0) && (statusReplicas == null || statusReplicas == 0)) {
			return ResourceStatus.STABLE;

		}
		// pending case
		if (updatedReplicas == null || (desiredReplicas != null && desiredReplicas > updatedReplicas)
				|| (statusReplicas != null && statusReplicas > updatedReplicas)
				|| (availableReplicas == null || availableReplicas < updatedReplicas)
				|| (readyReplicas == null || (desiredReplicas != null && desiredReplicas > readyReplicas))) {
			return ResourceStatus.PENDING;
		}
		return ResourceStatus.STABLE;
	}

	@Override
	public int getWeight() {
	    return ResourceKind.DEPLOYMENT.getWeight();
	}
}
