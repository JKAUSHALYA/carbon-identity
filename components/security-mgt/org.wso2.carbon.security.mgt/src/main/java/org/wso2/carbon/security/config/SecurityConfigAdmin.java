/*
 * Copyright (c) 2007, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.security.config;

import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.OMAttribute;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMFactory;
import org.apache.axiom.om.OMNamespace;
import org.apache.axiom.om.OMText;
import org.apache.axiom.om.impl.builder.StAXOMBuilder;
import org.apache.axiom.om.util.AXIOMUtil;
import org.apache.axis2.AxisFault;
import org.apache.axis2.description.AxisBinding;
import org.apache.axis2.description.AxisEndpoint;
import org.apache.axis2.description.AxisModule;
import org.apache.axis2.description.AxisService;
import org.apache.axis2.description.Parameter;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.neethi.Assertion;
import org.apache.neethi.Policy;
import org.apache.neethi.PolicyComponent;
import org.apache.neethi.PolicyEngine;
import org.apache.neethi.PolicyReference;
import org.apache.neethi.builders.xml.XmlPrimtiveAssertion;
import org.apache.rampart.policy.RampartPolicyBuilder;
import org.apache.rampart.policy.RampartPolicyData;
import org.apache.rampart.policy.model.CryptoConfig;
import org.apache.rampart.policy.model.KerberosConfig;
import org.apache.rampart.policy.model.RampartConfig;
import org.apache.ws.secpolicy.WSSPolicyException;
import org.apache.ws.secpolicy.model.SecureConversationToken;
import org.apache.ws.secpolicy.model.Token;
import org.apache.ws.security.handler.WSHandlerConstants;
import org.wso2.carbon.CarbonConstants;
import org.wso2.carbon.base.ServerConfiguration;
import org.wso2.carbon.core.RegistryResources;
import org.wso2.carbon.core.Resources;
import org.wso2.carbon.core.util.CryptoException;
import org.wso2.carbon.core.util.CryptoUtil;
import org.wso2.carbon.core.util.KeyStoreManager;
import org.wso2.carbon.core.util.KeyStoreUtil;
import org.wso2.carbon.registry.core.Registry;
import org.wso2.carbon.registry.core.Resource;
import org.wso2.carbon.registry.core.exceptions.RegistryException;
import org.wso2.carbon.registry.core.session.UserRegistry;
import org.wso2.carbon.security.SecurityConfigException;
import org.wso2.carbon.security.SecurityConstants;
import org.wso2.carbon.security.SecurityScenario;
import org.wso2.carbon.security.SecurityScenarioDatabase;
import org.wso2.carbon.security.SecurityServiceHolder;
import org.wso2.carbon.security.config.service.KerberosConfigData;
import org.wso2.carbon.security.config.service.SecurityConfigData;
import org.wso2.carbon.security.config.service.SecurityScenarioData;
import org.wso2.carbon.security.pox.POXSecurityHandler;
import org.wso2.carbon.security.util.SecurityTokenStore;
import org.wso2.carbon.security.util.ServerCrypto;
import org.wso2.carbon.security.util.ServicePasswordCallbackHandler;
import org.wso2.carbon.user.core.AuthorizationManager;
import org.wso2.carbon.user.core.UserCoreConstants;
import org.wso2.carbon.user.core.UserRealm;
import org.wso2.carbon.user.core.UserStoreException;
import org.wso2.carbon.utils.CarbonUtils;
import org.wso2.carbon.utils.ServerException;
import org.wso2.carbon.utils.deployment.GhostDeployerUtils;

import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.security.auth.callback.CallbackHandler;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Admin service for configuring Security scenarios
 */
public class SecurityConfigAdmin {

    public static final String USER = "rampart.config.user";
    public static final String IDENTITY_CONFIG_DIR = "identity";
    private static final String SEC_LABEL = "sec";
    private static Log log = LogFactory.getLog(SecurityConfigAdmin.class);
    private AxisConfiguration axisConfig = null;
    private CallbackHandler callback = null;
    private Registry registry = null;
    private UserRegistry govRegistry = null;
    private UserRealm realm = null;

    public SecurityConfigAdmin(AxisConfiguration config) throws SecurityConfigException {
        this.axisConfig = config;

        try {
            this.registry = SecurityServiceHolder.getRegistryService().getConfigSystemRegistry();
            this.govRegistry = SecurityServiceHolder.getRegistryService().getGovernanceSystemRegistry();
        } catch (Exception e) {
            String msg = "Error when retrieving a registry instance";
            log.error(msg);
            throw new SecurityConfigException(msg, e);
        }
    }

    public SecurityConfigAdmin(AxisConfiguration config, Registry reg, CallbackHandler cb) {
        this.axisConfig = config;
        this.registry = reg;
        this.callback = cb;

        try {
            this.govRegistry = SecurityServiceHolder.getRegistryService().getGovernanceSystemRegistry(
                    ((UserRegistry) reg).getTenantId());
        } catch (Exception e) {
            // TODO : handle this exception properly.
            log.error("Error when obtaining the governance registry instance.", e);
        }
    }

    public SecurityConfigAdmin(UserRealm realm, Registry registry, AxisConfiguration config) throws SecurityConfigException {
        this.axisConfig = config;
        this.registry = registry;
        this.realm = realm;

        try {
            this.govRegistry = SecurityServiceHolder.getRegistryService().getGovernanceSystemRegistry(
                    ((UserRegistry) registry).getTenantId());
        } catch (Exception e) {
            log.error("Error when obtaining the governance registry instance.");
            throw new SecurityConfigException(
                    "Error when obtaining the governance registry instance.", e);
        }
    }

    public SecurityScenarioData getSecurityScenario(String sceneId) throws SecurityConfigException {
        SecurityScenarioData data = null;
        SecurityScenario scenario = SecurityScenarioDatabase.get(sceneId);
        if (scenario != null) {
            data = new SecurityScenarioData();
            data.setCategory(scenario.getCategory());
            data.setDescription(scenario.getDescription());
            data.setScenarioId(scenario.getScenarioId());
            data.setSummary(scenario.getSummary());
        }
        return data;
    }

    public SecurityScenarioData getCurrentScenario(String serviceName)
            throws SecurityConfigException {

        AxisService service = axisConfig.getServiceForActivation(serviceName);
        SecurityScenarioData data = null;
        if (service == null) {
            // try to find it from the transit ghost map
            try {
                service = GhostDeployerUtils
                        .getTransitGhostServicesMap(axisConfig).get(serviceName);
            } catch (AxisFault axisFault) {
                log.error("Error while reading Transit Ghosts map", axisFault);
            }
            if (service == null) {
                throw new SecurityConfigException("AxisService is Null");
            }
        }
        SecurityScenario scenario = this.readCurrentScenario(serviceName);
        if (scenario != null) {
            data = new SecurityScenarioData();
            data.setCategory(scenario.getCategory());
            data.setDescription(scenario.getDescription());
            data.setScenarioId(scenario.getScenarioId());
            data.setSummary(scenario.getSummary());
        }

        return data;
    }

    public String[] getRequiredModules(String serviceName, String moduleId) throws Exception {
        SecurityScenarioData securityScenarioData = getCurrentScenario(serviceName);

        if (securityScenarioData != null) {
            SecurityScenario securityScenario = SecurityScenarioDatabase.get(securityScenarioData
                    .getScenarioId());
            String[] moduleNames = (String[]) securityScenario.getModules()
                    .toArray(new String[securityScenario.getModules().size()]);
            return moduleNames;
        }

        return new String[0];
    }

    public void disableSecurityOnService(String serviceName) throws SecurityConfigException {
        AxisService service = axisConfig.getServiceForActivation(serviceName);
        String serviceGroupId = service.getAxisServiceGroup().getServiceGroupName();
        SecurityScenario scenario = readCurrentScenario(serviceName);
        if (scenario == null) {
            return;
        }
        removeSecurityPolicy(service, serviceName);

        String[] moduleNames = scenario.getModules().toArray(new String[scenario.getModules().size()]);

        // disengage modules
        for (String moduleName : moduleNames) {
            AxisModule module = service.getAxisConfiguration().getModule(moduleName);
            try {
                service.disengageModule(module);
            } catch (AxisFault axisFault) {
                throw new SecurityConfigException("Error while disengaging module :" + moduleName, axisFault);
            }
        }


        try {
            Parameter param = new Parameter();
            param.setName(WSHandlerConstants.PW_CALLBACK_REF);
            service.removeParameter(param);

            Parameter param2 = new Parameter();
            param2.setName("disableREST"); // TODO Find the constant
            service.removeParameter(param2);

            Parameter pathParam = service.getParameter(SecurityConstants.SECURITY_POLICY_PATH);
            if (pathParam != null) {
                service.removeParameter(pathParam);
            }

            //removing security scenarioID parameter from axis service
            Parameter scenarioIDParam = service.getParameter(SecurityConstants.SCENARIO_ID_PARAM_NAME);
            if (scenarioIDParam != null) {
                service.removeParameter(scenarioIDParam);
            }
        } catch (AxisFault axisFault) {
            throw new SecurityConfigException("Error while removing parameters from service on disable security ",
                    axisFault);
        }

        AuthorizationManager acAdmin = null;
        try {
            acAdmin = realm.getAuthorizationManager();
            String resourceName = serviceGroupId + "/" + serviceName;
            String[] roles = acAdmin.getAllowedRolesForResource(
                    resourceName,
                    UserCoreConstants.INVOKE_SERVICE_PERMISSION);
            for (int i = 0; i < roles.length; i++) {
                acAdmin.clearRoleAuthorization(roles[i], resourceName,
                        UserCoreConstants.INVOKE_SERVICE_PERMISSION);
            }
        } catch (UserStoreException e) {
            throw new SecurityConfigException("Error while removing authorization roles ", e);
        }

        SecurityServiceAdmin admin = new SecurityServiceAdmin(axisConfig, registry);
        try {
            admin.removeSecurityPolicyFromAllBindings(service, scenario.getWsuId());
        } catch (ServerException e) {
            throw new SecurityConfigException("Error while removing policy from all bindings", e);
        }

    }

    private void removeSecurityPolicy(AxisService service, String serviceName) throws SecurityConfigException {

        try {
            Registry configRegistry = registry;
            String servicePath = getRegistryServicePath(service);
            String policyResourcePath = servicePath + RegistryResources.POLICIES;
            if (configRegistry.resourceExists(policyResourcePath)) {
                configRegistry.delete(policyResourcePath);
            }
            if (service.getPolicySubject().getAttachedPolicyComponents() != null) {
                service.getPolicySubject().getAttachedPolicyComponents().clear();
            }

            Parameter scenarioIDParam = service.getParameter(SecurityConstants.SCENARIO_ID_PARAM_NAME);
            if (scenarioIDParam != null) {
                service.removeParameter(scenarioIDParam);
            }

        } catch (RegistryException ex) {
            throw new SecurityConfigException("Error occurred while removing security policy");
        } catch (AxisFault axisFault) {
            throw new SecurityConfigException("Error while removing scenario ID from axis service", axisFault);
        }
    }

    private KerberosConfigData readKerberosConfigurations(OMElement carbonSecConfig) throws SecurityConfigException {

        KerberosConfigData kerberosConfigData = null;
        if (carbonSecConfig != null) {
            OMElement kerberosElement = carbonSecConfig.getFirstChildWithName(new QName(SecurityConstants
                    .SECURITY_NAMESPACE, SecurityConstants.KERBEROS));
            if (kerberosElement != null) {
                kerberosConfigData = new KerberosConfigData();
                Map<String, String> kerberosProperties = getProperties(kerberosElement);
                if (kerberosProperties.get(KerberosConfig.SERVICE_PRINCIPLE_NAME) != null) {
                    kerberosConfigData.setServicePrincipleName(kerberosProperties.get(KerberosConfig
                            .SERVICE_PRINCIPLE_NAME));
                }
                if (kerberosProperties.get(KerberosConfig.SERVICE_PRINCIPLE_PASSWORD) != null) {
                    String encryptedString = kerberosProperties.get(KerberosConfig.SERVICE_PRINCIPLE_PASSWORD);
                    CryptoUtil cryptoUtil = CryptoUtil.getDefaultCryptoUtil();
                    try {
                        kerberosConfigData.setServicePrinciplePassword
                                (new String(cryptoUtil.base64DecodeAndDecrypt(encryptedString)));
                    } catch (CryptoException e) {
                        String msg = "Unable to decode and decrypt password string.";
                        log.warn(msg, e);
                    }
                }
            }
        }

        return kerberosConfigData;
    }

    private Map<String, String> getTrustProperties(OMElement carbonSecConfig) {
        OMElement trustElement = null;
        if (carbonSecConfig != null) {
            trustElement = carbonSecConfig.getFirstChildWithName(new QName(SecurityConstants
                    .SECURITY_NAMESPACE, SecurityConstants.TRUST));
        }
        return getProperties(trustElement);
    }

    private OMElement getCarbonSecConfigs(AxisService service) {
        java.util.Collection policies = service.getPolicySubject()
                .getAttachedPolicyComponents();
        Iterator policyComponents = policies.iterator();
        while (policyComponents.hasNext()) {
            PolicyComponent currentPolicyComponent = (PolicyComponent) policyComponents
                    .next();
            if (currentPolicyComponent instanceof Policy) {
                Policy policy = ((Policy) currentPolicyComponent);
                List it = (List) policy.getAlternatives().next();
                for (Iterator iter = it.iterator(); iter.hasNext(); ) {
                    Assertion assertion = (Assertion) iter.next();
                    if (assertion instanceof XmlPrimtiveAssertion) {
                        OMElement xmlPrimitiveAssertion = (((XmlPrimtiveAssertion) assertion).getValue());
                        if (SecurityConstants.CARBON_SEC_CONFIG.equals(xmlPrimitiveAssertion.getLocalName())) {
                            return (((XmlPrimtiveAssertion) assertion).getValue());
                        }
                    }
                }
            }
        }
        return null;
    }

    private RampartConfig getRampartConfigs(AxisService service) {
        java.util.Collection policies = service.getPolicySubject()
                .getAttachedPolicyComponents();
        Iterator policyComponents = policies.iterator();
        while (policyComponents.hasNext()) {
            PolicyComponent currentPolicyComponent = (PolicyComponent) policyComponents
                    .next();
            if (currentPolicyComponent instanceof Policy) {
                Policy policy = ((Policy) currentPolicyComponent);
                List it = (List) policy.getAlternatives().next();
                for (Iterator iter = it.iterator(); iter.hasNext(); ) {
                    Assertion assertion = (Assertion) iter.next();
                    if (assertion instanceof RampartConfig) {
                        return (RampartConfig) assertion;
                    }
                }
            }
        }
        return null;
    }

    private String getEncryptedPassword(String password) throws SecurityConfigException {
        CryptoUtil cryptoUtil = CryptoUtil.getDefaultCryptoUtil();
        try {
            return cryptoUtil.encryptAndBase64Encode(password.getBytes());
        } catch (CryptoException e) {
            String msg = "Unable to encrypt and encode password string.";
            log.error(msg, e);
            throw new SecurityConfigException(msg, e);
        }
    }

    private String getRegistryServicePath(AxisService service) {

        StringBuilder pathValue = new StringBuilder();
        return (pathValue
                .append(RegistryResources.SERVICE_GROUPS)
                .append(service.getAxisServiceGroup().getServiceGroupName())
                .append(RegistryResources.SERVICES)
                .append(service.getName())).toString();
    }

    public void activateUsernameTokenAuthentication(String serviceName, String[] userGroups)
            throws SecurityConfigException {

        // TODO Remove

    }


    public void applySecurity(String serviceName, String scenarioId, KerberosConfigData kerberosConfigurations)
            throws SecurityConfigException {

        if (kerberosConfigurations == null) {
            log.error("Kerberos configurations provided are invalid.");
            throw new SecurityConfigException("Kerberos configuration parameters are null. " +
                    "Please specify valid kerberos configurations.");
        }

        AxisService service = axisConfig.getServiceForActivation(serviceName);
        if (service == null) {
            throw new SecurityConfigException("nullService");
        }

        String serviceGroupId = service.getAxisServiceGroup().getServiceGroupName();
        // Disable security if already a policy is applied
        this.disableSecurityOnService(serviceName); //todo fix the method

        OMElement policyElement = loadPolicyAsXML(scenarioId, null);
        addUserParameters(policyElement, null, null, null, kerberosConfigurations, false);

        policyElement.addChild(buildRampartConfigXML(null, null, kerberosConfigurations));
        Policy policy = PolicyEngine.getPolicy(policyElement);
        removeSecurityPolicy(service, serviceName);
        service.getPolicySubject().attachPolicy(policy);
        persistPolicy(service, policyElement, policy.getId());

        try {
            CallbackHandler handler = null;
            if (callback == null) {
                // This will break kerberos from management console UI
                handler = new ServicePasswordCallbackHandler(null, serviceGroupId, service.getName(),
                        registry, realm);
            } else {
                handler = this.callback;
            }
            Parameter param = new Parameter();
            param.setName(WSHandlerConstants.PW_CALLBACK_REF);
            param.setValue(handler);
            service.addParameter(param);

            this.getPOXCache().remove(serviceName);
            Cache<String, String> cache = getPOXCache();
            if (cache != null) {
                cache.remove(serviceName);
            }
            engageModules(scenarioId, serviceName, service);
            SecurityServiceAdmin secAdmin = new SecurityServiceAdmin(axisConfig, registry);
            try {
                secAdmin.addSecurityPolicyToAllBindings(service, policy);
            } catch (ServerException e) {
                throw new SecurityConfigException("Error while applying policy to all bindings");
            }

            //Adding the security scenario ID parameter to the axisService
            //This parameter can be used to get the applied security scenario
            //without reading the service meta data file.
            try {
                Parameter params = new Parameter();
                params.setName(SecurityConstants.SCENARIO_ID_PARAM_NAME);
                params.setValue(scenarioId);
                service.addParameter(params);
            } catch (AxisFault axisFault) {
                log.error("Error while adding Scenario ID parameter", axisFault);
            }

        } catch (RegistryException ex) {
            throw new SecurityConfigException("Error occurred while creating callback handler");
        } catch (AxisFault ex) {
            throw new SecurityConfigException("Error occurred while adding callback parameter");
        }

        disableRESTCalls(serviceName, scenarioId);
        this.getPOXCache().remove(serviceName);
    }


    public void applySecurity(String serviceName, String scenarioId, String policyPath,
                              String[] trustedStores, String privateStore,
                              String[] userGroups) throws SecurityConfigException {

        AxisService service = axisConfig.getServiceForActivation(serviceName);
        if (service == null) {
            throw new SecurityConfigException("Service not available.");
        }

        if (userGroups != null) {
            Arrays.sort(userGroups);
            if (Arrays.binarySearch(userGroups, CarbonConstants.REGISTRY_ANONNYMOUS_ROLE_NAME) > -1) {
                log.error("Security breach. A user is attempting to enable anonymous for UT access");
                throw new SecurityConfigException("Invalid data provided"); // obscure error message
            }
        }
        this.disableSecurityOnService(serviceName);

        if (GhostDeployerUtils.isGhostService(service)) {
            try {
                service = GhostDeployerUtils.deployActualService(axisConfig, service);
            } catch (AxisFault axisFault) {
                log.error("Error while loading actual service from Ghost", axisFault);
            }
        }
        disableRESTCalls(serviceName, scenarioId);
        OMElement policyElement = loadPolicyAsXML(scenarioId, policyPath);
        SecurityScenario scenario = SecurityScenarioDatabase.get(scenarioId);
        boolean isTrustEnabled = scenario.getModules().contains(SecurityConstants.TRUST_MODULE);

        if ((isTrustEnabled || (userGroups != null && userGroups
                .length > 0)) && !SecurityConstants.POLICY_FROM_REG_SCENARIO.equals(scenarioId)) {
            addUserParameters(policyElement, trustedStores, privateStore, userGroups, null, isTrustEnabled);
        }
        // If policy is taken from registry (custom policy) it needs to have rampartConfigs defined it.
        if (StringUtils.isNotBlank(policyPath) && !SecurityConstants.POLICY_FROM_REG_SCENARIO.equals(scenarioId)) {
            policyElement.addChild(buildRampartConfigXML(privateStore, trustedStores, null));
        }
        Policy policy = PolicyEngine.getPolicy(policyElement);
        removeSecurityPolicy(service, serviceName);
        service.getPolicySubject().attachPolicy(policy);
        persistPolicy(service, policyElement, policy.getId());
        engageModules(scenarioId, serviceName, service);
        try {
            String serviceGroupId = service.getAxisServiceGroup().getServiceGroupName();
            CallbackHandler handler;
            if (callback == null) {
                // This will break kerberos from management console UI
                handler = new ServicePasswordCallbackHandler(null, serviceGroupId, service.getName(),
                        registry, realm);
            } else {
                handler = this.callback;
            }
            Parameter param = new Parameter();
            param.setName(WSHandlerConstants.PW_CALLBACK_REF);
            param.setValue(handler);
            service.addParameter(param);

            if (userGroups != null) {
                for (String value : userGroups) {
                    AuthorizationManager acAdmin = realm.getAuthorizationManager();

                    acAdmin.authorizeRole(value, serviceGroupId + "/" + service.getName(),
                            UserCoreConstants.INVOKE_SERVICE_PERMISSION);
                }
            }

            this.getPOXCache().remove(serviceName);
            Cache<String, String> cache = getPOXCache();
            if (cache != null) {
                cache.remove(serviceName);
            }

            //Adding the security scenario ID parameter to the axisService
            //This parameter can be used to get the applied security scenario
            //without reading the service meta data file.
            try {
                Parameter params = new Parameter();
                params.setName(SecurityConstants.SCENARIO_ID_PARAM_NAME);
                params.setValue(scenarioId);
                service.addParameter(params);
            } catch (AxisFault axisFault) {
                log.error("Error while adding Scenario ID parameter", axisFault);
            }

        } catch (RegistryException ex) {
            throw new SecurityConfigException("Error occurred while creating callback handler");
        } catch (AxisFault ex) {
            throw new SecurityConfigException("Error occurred while adding callback parameter");
        } catch (UserStoreException e) {
            throw new SecurityConfigException("Error occurred while getting AuthorizationManager");
        }

        SecurityServiceAdmin admin = new SecurityServiceAdmin(axisConfig, registry);
        try {
            admin.addSecurityPolicyToAllBindings(service, policy);
        } catch (ServerException e) {
            throw new SecurityConfigException("Error while applying policy to all bindings");
        }
    }

    private OMElement buildRampartConfigXML(String privateStore, String[] trustedStores,
                                            KerberosConfigData kerberosConfig) throws SecurityConfigException {

        ByteArrayOutputStream out = null;
        XMLStreamWriter writer = null;
        OMElement rampartConfigElement = null;
        try {
            Properties props = getServerCryptoProperties(privateStore, trustedStores);
            RampartConfig rampartConfig = new RampartConfig();
            // rampartConfig.setTokenStoreClass(SimpleTokenStore.class.getName());
            populateRampartConfig(rampartConfig, props, kerberosConfig);
            if (rampartConfig != null) {
                //addRampartConfigs(policyElement, rampartConfig);
                out = new ByteArrayOutputStream();
                writer = XMLOutputFactory.newInstance().createXMLStreamWriter(out);
                rampartConfig.serialize(writer);
                writer.flush();
                writer.close();
                out.close();
                out.flush();
                rampartConfigElement = AXIOMUtil.stringToOM(out.toString());
            }
        } catch (Exception e) {
            throw new SecurityConfigException("Error while building rampart configs");
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    log.error("Error while closing output stream", e);
                }
                if (writer != null) {
                    try {
                        writer.close();
                    } catch (XMLStreamException e) {
                        log.error("Error while closing xml stream writer", e);
                    }
                }
            }
        }
        return rampartConfigElement;
    }

    private void persistPolicy(AxisService service, OMElement policy, String policyID) {

        //        Registry registryToLoad = SecurityServiceHolder.getRegistryService().getConfigSystemRegistry();
        try {
            Resource resource = registry.newResource();
            resource.setContent(policy.toString());
            String servicePath = getRegistryServicePath(service);
            String policyResourcePath = servicePath + RegistryResources.POLICIES + policyID;
            registry.put(policyResourcePath, resource);
        } catch (RegistryException e) {
            log.error("Error occurred while persisting policy", e);
        }
    }

    private void addUserParameters(OMElement policyElement, String[] trustedStores, String privateStore,
                                   String[] userGroups, KerberosConfigData kerberosConfigData, boolean isTrusEnabled
    ) throws SecurityConfigException {

        OMFactory factory = OMAbstractFactory.getOMFactory();
        OMNamespace secElement = factory.createOMNamespace(SecurityConstants.SECURITY_NAMESPACE, SEC_LABEL);
        OMElement carbonSecElement = factory.createOMElement(SecurityConstants.CARBON_SEC_CONFIG, secElement);
        OMElement kerberosElement = factory.createOMElement(SecurityConstants.KERBEROS, secElement);
        OMElement trustElement = null;

        if ((trustedStores != null || privateStore != null) && isTrusEnabled) {
            trustElement = factory.createOMElement(SecurityConstants.TRUST, secElement);
            if (trustedStores != null && trustedStores.length > 0) {
                OMElement trustStorePropertyElement = factory.createOMElement(SecurityConstants.PROPERTY_LABEL, secElement);
                OMAttribute propertyNameAttribute = factory.createOMAttribute(SecurityConstants.NAME_LABEL, null,
                        ServerCrypto.PROP_ID_TRUST_STORES);
                trustStorePropertyElement.addAttribute(propertyNameAttribute);
                OMText storePropertyValue = factory.createOMText(trustStorePropertyElement, getArrayAsString(trustedStores));
                trustStorePropertyElement.addChild(storePropertyValue);
                trustElement.addChild(trustStorePropertyElement);
                carbonSecElement.addChild(trustElement);

            }
            if (privateStore != null) {
                OMElement privateStorePropertyElement = factory.createOMElement(SecurityConstants.PROPERTY_LABEL, secElement);
                OMAttribute propertyNameAttribute = factory.createOMAttribute(SecurityConstants.NAME_LABEL, null, ServerCrypto.PROP_ID_PRIVATE_STORE);
                privateStorePropertyElement.addAttribute(propertyNameAttribute);
                OMText storePropertyValue = factory.createOMText(privateStorePropertyElement, privateStore);

                privateStorePropertyElement.addChild(storePropertyValue);
                trustElement.addChild(privateStorePropertyElement);

                ServerConfiguration serverConfig = ServerConfiguration.getInstance();
                String keyAlias = null;
                keyAlias = serverConfig.getFirstProperty("Security.KeyStore.KeyAlias");

                OMElement aliasPropertyElement = factory.createOMElement(SecurityConstants.PROPERTY_LABEL, secElement);
                OMAttribute aliasPropertyNameAttribute = factory.createOMAttribute(SecurityConstants.NAME_LABEL, null,
                        ServerCrypto.PROP_ID_DEFAULT_ALIAS);
                aliasPropertyElement.addAttribute(aliasPropertyNameAttribute);
                OMText aliasPropertyValue = factory.createOMText(aliasPropertyElement, keyAlias);

                aliasPropertyElement.addChild(aliasPropertyValue);
                trustElement.addChild(aliasPropertyElement);

                carbonSecElement.addChild(trustElement);

            }

        }

        if (userGroups != null && userGroups.length > 0) {
            OMElement authorizationElement = factory.createOMElement(SecurityConstants.AUTHORIZATION, secElement);
            OMElement propertyElement = factory.createOMElement(SecurityConstants.PROPERTY_LABEL, secElement);
            OMAttribute propertyNameAttribute = factory.createOMAttribute(SecurityConstants.NAME_LABEL, null, "org.wso2.carbon.security.allowedroles");
            propertyElement.addAttribute(propertyNameAttribute);
            OMText propertyValue = factory.createOMText(propertyElement, getArrayAsString(userGroups));

            propertyElement.addChild(propertyValue);
            authorizationElement.addChild(propertyElement);
            carbonSecElement.addChild(authorizationElement);
            policyElement.addChild(carbonSecElement);
        }

        if (kerberosConfigData != null) {

            if (StringUtils.isNotEmpty(kerberosConfigData.getServicePrincipleName())) {
                OMElement principalNamePropertyElement = factory.createOMElement(SecurityConstants.PROPERTY_LABEL, secElement);
                OMAttribute propertyNameAttribute = factory.createOMAttribute(SecurityConstants.NAME_LABEL, null, KerberosConfig.SERVICE_PRINCIPLE_NAME);
                principalNamePropertyElement.addAttribute(propertyNameAttribute);
                OMText principalNameValue = factory.createOMText(principalNamePropertyElement,
                        kerberosConfigData.getServicePrincipleName());
                principalNamePropertyElement.addChild(principalNameValue);
                kerberosElement.addChild(principalNamePropertyElement);
            }

            if (StringUtils.isNotEmpty(kerberosConfigData.getServicePrinciplePassword())) {
                OMElement principalPasswordPropertyElement = factory.createOMElement(SecurityConstants.PROPERTY_LABEL, secElement);
                OMAttribute propertyNameAttribute = factory.createOMAttribute(SecurityConstants.NAME_LABEL, null, KerberosConfig.SERVICE_PRINCIPLE_PASSWORD);
                OMAttribute propertyEncryptedAttribute = factory.createOMAttribute(SecurityConstants.ENCRYPTED, null, "true");
                principalPasswordPropertyElement.addAttribute(propertyNameAttribute);
                principalPasswordPropertyElement.addAttribute(propertyEncryptedAttribute);
                OMText principalPasswordValue = null;
                principalPasswordValue = factory.createOMText(principalPasswordPropertyElement,
                        getEncryptedPassword(kerberosConfigData.getServicePrinciplePassword()));
                principalPasswordPropertyElement.addChild(principalPasswordValue);
                kerberosElement.addChild(principalPasswordPropertyElement);

            }

            carbonSecElement.addChild(kerberosElement);
        }
        policyElement.addChild(carbonSecElement);
    }


    private String getArrayAsString(String[] userGroups) {
        StringBuffer groups = new StringBuffer();
        boolean isFirst = true;
        for (String group : userGroups) {
            if (isFirst) {
                groups.append(group);
                isFirst = false;
            } else {
                groups.append(",");
                groups.append(group);
            }
        }
        return groups.toString();
    }

    private OMElement loadPolicyAsXML(String scenarioId, String policyPath) throws SecurityConfigException {

        try {
            Registry registryToLoad = registry;
            String resourceUri = SecurityConstants.SECURITY_POLICY + "/" + scenarioId;
            if (policyPath != null &&
                    scenarioId.equals(SecurityConstants.POLICY_FROM_REG_SCENARIO)) {
                resourceUri = policyPath.substring(policyPath.lastIndexOf(':') + 1);
                String regIdentifier = policyPath.substring(0, policyPath.lastIndexOf(':'));
                if (SecurityConstants.GOVERNANCE_REGISTRY_IDENTIFIER.equals(regIdentifier)) {
                    registryToLoad = govRegistry;
                }
            }
            Resource resource = null;
            resource = registryToLoad.get(resourceUri);
            InputStream in = resource.getContentStream();
            XMLStreamReader parser = null;
            parser = XMLInputFactory.newInstance().createXMLStreamReader(in);
            StAXOMBuilder builder = new StAXOMBuilder(parser);
            OMElement policyElement = builder.getDocumentElement();

            if (policyPath != null &&
                    scenarioId.equals(SecurityConstants.POLICY_FROM_REG_SCENARIO)) {
                OMAttribute att = policyElement.getAttribute(SecurityConstants.POLICY_ID_QNAME);
                if (att != null) {
                    att.setAttributeValue(SecurityConstants.POLICY_FROM_REG_SCENARIO);
                }
            }
            return policyElement;
        } catch (RegistryException e) {
            throw new SecurityConfigException("Error occurred while loading policy.", e);
        } catch (XMLStreamException e) {
            throw new SecurityConfigException("Error occurred while loading policy.", e);
        }

    }


    protected void engageModules(String scenarioId, String serviceName, AxisService axisService)
            throws SecurityConfigException {
        SecurityScenario securityScenario = SecurityScenarioDatabase.get(scenarioId);
        String[] moduleNames = (String[]) securityScenario.getModules()
                .toArray(new String[securityScenario.getModules().size()]);
        // handle each module required
        try {

            for (String modName : moduleNames) {
                AxisModule module = axisService.getAxisConfiguration().getModule(modName);
                // engage at axis2
                axisService.disengageModule(module);
                axisService.engageModule(module);
            }

        } catch (AxisFault e) {
            log.error(e);
            throw new SecurityConfigException("Error in engaging modules", e);
        }
    }

    protected void disableRESTCalls(String serviceName, String scenrioId)
            throws SecurityConfigException {

        if (scenrioId.equals(SecurityConstants.USERNAME_TOKEN_SCENARIO_ID)) {
            return;
        }

        try {
            AxisService service = axisConfig.getServiceForActivation(serviceName);
            if (service == null) {
                throw new SecurityConfigException("nullService");
            }

            Parameter param = new Parameter();
            param.setName("disableREST"); // TODO Find the constant
            param.setValue(Boolean.TRUE.toString());
            service.addParameter(param);

        } catch (AxisFault e) {
            log.error(e);
            throw new SecurityConfigException("disablingREST", e);
        }

    }


    public void populateRampartConfig(RampartConfig rampartConfig, Properties props,
                                      KerberosConfigData kerberosConfigurations)
            throws SecurityConfigException {
        if (rampartConfig != null) {

            if (kerberosConfigurations != null) {

                Properties kerberosProperties = new Properties();
                kerberosProperties.setProperty(KerberosConfig.SERVICE_PRINCIPLE_NAME,
                        kerberosConfigurations.getServicePrincipleName());

                KerberosConfig kerberosConfig = new KerberosConfig();
                kerberosConfig.setProp(kerberosProperties);

                // Set system wide kerberos configurations

                String carbonSecurityConfigurationPath = CarbonUtils.getCarbonConfigDirPath() + File.separatorChar +
                        IDENTITY_CONFIG_DIR;

                String krbFile = carbonSecurityConfigurationPath + File.separatorChar
                        + KerberosConfigData.KERBEROS_CONFIG_FILE_NAME;

                File krbFileObject = new File(krbFile);

                if (!krbFileObject.exists()) {
                    throw new SecurityConfigException("Kerberos configuration file not found at " + krbFile);
                }

                log.info("Setting " + KerberosConfigData.KERBEROS_CONFIG_FILE_SYSTEM_PROPERTY +
                        " to kerberos configuration file " + krbFile);

                System.setProperty(KerberosConfigData.KERBEROS_CONFIG_FILE_SYSTEM_PROPERTY, krbFile);

                rampartConfig.setKerberosConfig(kerberosConfig);

            } else {
                if (!props.isEmpty()) {
                    // Encryption crypto config
                    {
                        CryptoConfig encrCryptoConfig = new CryptoConfig();
                        encrCryptoConfig.setProvider(ServerCrypto.class.getName());
                        encrCryptoConfig.setProp(props);
                        encrCryptoConfig.setCacheEnabled(true);
                        encrCryptoConfig.setCryptoKey(ServerCrypto.PROP_ID_PRIVATE_STORE);
                        rampartConfig.setEncrCryptoConfig(encrCryptoConfig);
                    }
                    {
                        CryptoConfig signatureCryptoConfig = new CryptoConfig();
                        signatureCryptoConfig.setProvider(ServerCrypto.class.getName());
                        signatureCryptoConfig.setProp(props);
                        signatureCryptoConfig.setCacheEnabled(true);
                        signatureCryptoConfig.setCryptoKey(ServerCrypto.PROP_ID_PRIVATE_STORE);
                        rampartConfig.setSigCryptoConfig(signatureCryptoConfig);
                    }
                }

                rampartConfig.setEncryptionUser(WSHandlerConstants.USE_REQ_SIG_CERT);
                rampartConfig.setUser(props.getProperty(SecurityConstants.USER));

                // Get ttl and timeskew params from axis2 xml
                int ttl = RampartConfig.DEFAULT_TIMESTAMP_TTL;
                int timeSkew = RampartConfig.DEFAULT_TIMESTAMP_MAX_SKEW;

                rampartConfig.setTimestampTTL(Integer.toString(ttl));
                rampartConfig.setTimestampMaxSkew(Integer.toString(timeSkew));

                //this will check for TokenStoreClassName property under Security in carbon.xml
                //if it is not found, default token store class will be set
                String tokenStoreClassName = ServerConfiguration.getInstance().getFirstProperty("Security.TokenStoreClassName");
                if (tokenStoreClassName == null) {
                    rampartConfig.setTokenStoreClass(SecurityTokenStore.class.getName());
                } else {
                    rampartConfig.setTokenStoreClass(tokenStoreClassName);
                }
            }
        }
    }

    public Properties getServerCryptoProperties(String privateStore, String[] trustedCertStores)
            throws Exception {
        Properties props = new Properties();

        int tenantId = ((UserRegistry) registry).getTenantId();

        if (trustedCertStores != null && trustedCertStores.length > 0) {
            StringBuilder trustString = new StringBuilder();
            for (String trustedCertStore : trustedCertStores) {
                if (trustString.length() > 0) {
                    trustString.append(",");
                }
                trustString.append(trustedCertStore);
            }

            if (trustedCertStores.length != 0) {
                props.setProperty(ServerCrypto.PROP_ID_TRUST_STORES, trustString.toString());
            }
        }

        if (privateStore != null) {
            props.setProperty(ServerCrypto.PROP_ID_PRIVATE_STORE, privateStore);

            KeyStoreManager keyMan = KeyStoreManager.getInstance(tenantId);
            KeyStore ks = keyMan.getKeyStore(privateStore);

            String privKeyAlias = KeyStoreUtil.getPrivateKeyAlias(ks);
            props.setProperty(ServerCrypto.PROP_ID_DEFAULT_ALIAS, privKeyAlias);
            props.setProperty(USER, privKeyAlias);
        }

        if (privateStore != null || (trustedCertStores != null && trustedCertStores.length > 0)) {
            //Set the tenant-ID in the properties
            props.setProperty(ServerCrypto.PROP_ID_TENANT_ID,
                    Integer.toString(tenantId));
        }

        return props;
    }

    /**
     * Expose this service only via the specified transport
     *
     * @param serviceId          service name
     * @param transportProtocols transport protocols to expose
     * @throws AxisFault                                        axisfault
     * @throws org.wso2.carbon.security.SecurityConfigException ex
     */
    public void setServiceTransports(String serviceId, List<String> transportProtocols)
            throws SecurityConfigException, AxisFault {

        AxisService axisService = axisConfig.getServiceForActivation(serviceId);

        if (axisService == null) {
            throw new SecurityConfigException("nullService");
        }

        List<String> transports = new ArrayList<>();
        for (int i = 0; i < transportProtocols.size(); i++) {
            transports.add(transportProtocols.get(i));
        }
        axisService.setExposedTransports(transports);

        if (log.isDebugEnabled()) {
            log.debug("Successfully add selected transport bindings to service " + serviceId);
        }
    }

    /**
     * Check the policy to see whether the service should only be exposed in
     * HTTPS
     *
     * @param policy service policy
     * @return returns true if the service should only be exposed in HTTPS
     * @throws org.wso2.carbon.security.SecurityConfigException ex
     */
    public boolean isHttpsTransportOnly(Policy policy) throws SecurityConfigException {

        // When there is a transport binding sec policy assertion,
        // the service should be exposed only via HTTPS
        boolean httpsRequired = false;

        try {
            Iterator alternatives = policy.getAlternatives();
            if (alternatives.hasNext()) {
                List it = (List) alternatives.next();

                RampartPolicyData rampartPolicyData = RampartPolicyBuilder.build(it);
                if (rampartPolicyData.isTransportBinding()) {
                    httpsRequired = true;
                } else if (rampartPolicyData.isSymmetricBinding()) {
                    Token encrToken = rampartPolicyData.getEncryptionToken();
                    if (encrToken instanceof SecureConversationToken) {
                        Policy bsPol = ((SecureConversationToken) encrToken).getBootstrapPolicy();
                        Iterator alts = bsPol.getAlternatives();
                        List bsIt = (List) alts.next();
                        RampartPolicyData bsRampartPolicyData = RampartPolicyBuilder.build(bsIt);
                        httpsRequired = bsRampartPolicyData.isTransportBinding();
                    }
                }
            }
        } catch (WSSPolicyException e) {
            log.error("Error in checking http transport only", e);
            throw new SecurityConfigException("Error in checking http transport only", e);
        }

        return httpsRequired;
    }

    /**
     * Get "https" transports in the AxisConfig
     *
     * @return list
     */
    public List<String> getHttpsTransports() {

        List<String> httpsTransports = new ArrayList<>();
        for (Iterator iter = axisConfig.getTransportsIn().keySet().iterator(); iter.hasNext(); ) {
            String transport = (String) iter.next();
            if (transport.toLowerCase().indexOf(SecurityConstants.HTTPS_TRANSPORT) != -1) {
                httpsTransports.add(transport);
            }
        }
        return httpsTransports;
    }

    /**
     * Get all transports in AxisConfig
     *
     * @return list of all transports
     */
    public List<String> getAllTransports() {

        List<String> allTransports = new ArrayList<>();
        for (Iterator iter = axisConfig.getTransportsIn().keySet().iterator(); iter.hasNext(); ) {
            String transport = (String) iter.next();
            allTransports.add(transport);
        }
        return allTransports;
    }

    public SecurityConfigData getSecurityConfigData(String serviceName, String scenarioId,
                                                    String policyPath) throws SecurityConfigException {

        SecurityConfigData data = null;
        AxisService service = axisConfig.getServiceForActivation(serviceName);
        String serviceGroupId = service.getAxisServiceGroup().getServiceGroupName();
        try {
            if (scenarioId == null) {
                return data;
            }

            /**
             * Scenario ID can either be a default one (out of 15) or "policyFromRegistry", which
             * means the current scenario refers to a custom policy from registry. If that is the
             * case, we can't read the current scenario from the WSU ID. Therefore, we don't
             * check the scenario ID. In default cases, we check it.
             */
            if (scenarioId.equals(SecurityConstants.POLICY_FROM_REG_SCENARIO)) {
                Parameter param = service.getParameter(SecurityConstants.SECURITY_POLICY_PATH);
                if (param == null || !policyPath.equals(param.getValue())) {
                    return data;
                }
            } else {
                SecurityScenario scenario = readCurrentScenario(serviceName);
                if (scenario == null || !scenario.getScenarioId().equals(scenarioId)) {
                    return data;
                }
            }

            OMElement carbonSecConfig = getCarbonSecConfigs(service);
            RampartConfig rampartConfigs = getRampartConfigs(service);
            Map<String, String> trustProperties = getTrustProperties(carbonSecConfig);

            data = new SecurityConfigData();

            //may be we don't need this in the new persistence model
            // String serviceXPath = PersistenceUtils.getResourcePath(service);
            AuthorizationManager acReader = realm.getAuthorizationManager();
            String[] roles = acReader.getAllowedRolesForResource(
                    serviceGroupId + "/" + serviceName,
                    UserCoreConstants.INVOKE_SERVICE_PERMISSION);

            data.setUserGroups(roles);

            String privateStore = getProperty(rampartConfigs, trustProperties, ServerCrypto.PROP_ID_PRIVATE_STORE);
            if (StringUtils.isNotBlank(privateStore)) {
                data.setPrivateStore(privateStore);
            }

            String trustedStores = getProperty(rampartConfigs, trustProperties, ServerCrypto.PROP_ID_TRUST_STORES);
            if (StringUtils.isNotBlank(trustedStores)) {
                data.setTrustedKeyStores(trustedStores.split(","));
            }

            return data;

        } catch (UserStoreException e) {
            log.error("Error in getting security config data. Failed to get Authorization Manager", e);
        }
        return data;
    }

    public SecurityScenario readCurrentScenario(String serviceName) throws SecurityConfigException {
        SecurityScenario scenario = null;

        AxisService service = axisConfig.getServiceForActivation(serviceName);
        String serviceGroupId = null;
        try {
            if (service == null) {
                // try to find it from the transit ghost map
                try {
                    service = GhostDeployerUtils
                            .getTransitGhostServicesMap(axisConfig).get(serviceName);
                } catch (AxisFault axisFault) {
                    log.error("Error while reading Transit Ghosts map", axisFault);
                }
                if (service == null) {
                    throw new SecurityConfigException("AxisService is Null" + service);
                }
            }

            // after this point, we are going to do some policy related operations in the
            // AxisService object. Therefore, if the existing service is a ghost service, deploy
            // the actual one

            //TODO:When having this part it adds two policy parts to wsdl
//            if (GhostDeployerUtils.isGhostService(service)) {
//                service = GhostDeployerUtils.deployActualService(axisConfig, service);
//            }

            scenario = null;
            Map endPointMap = service.getEndpoints();
            for (Object o : endPointMap.entrySet()) {
                SecurityScenario epSecurityScenario = null;

                Map.Entry entry = (Map.Entry) o;
                AxisEndpoint point = (AxisEndpoint) entry.getValue();
                AxisBinding binding = point.getBinding();
                java.util.Collection policies = binding.getPolicySubject()
                        .getAttachedPolicyComponents();
                Iterator policyComponents = policies.iterator();
                String policyId = null;
                while (policyComponents.hasNext()) {
                    PolicyComponent currentPolicyComponent = (PolicyComponent) policyComponents
                            .next();
                    if (currentPolicyComponent instanceof Policy) {
                        policyId = ((Policy) currentPolicyComponent).getId();
                    } else if (currentPolicyComponent instanceof PolicyReference) {
                        policyId = ((PolicyReference) currentPolicyComponent).getURI().substring(1);
                    }
                    if (policyId != null) {
                        // Check whether this is a security scenario
                        epSecurityScenario = SecurityScenarioDatabase.getByWsuId(policyId);
                    }
                }

                // If a scenario is NOT applied to at least one non HTTP
                // binding,
                // we consider the service unsecured.
                if (epSecurityScenario == null) {
                    if (!binding.getName().getLocalPart().contains("HttpBinding")) {
                        scenario = epSecurityScenario;
                        break;
                    }
                } else {
                    scenario = epSecurityScenario;
                }
            }

            // If the binding level policies are not present, check whether there is a policy attached
            // at the service level. This is a fix for Securing Proxy Services.
            if (scenario == null) {
                java.util.Collection policies = service.getPolicySubject()
                        .getAttachedPolicyComponents();
                Iterator policyComponents = policies.iterator();
                String policyId = null;
                while (policyComponents.hasNext()) {
                    PolicyComponent currentPolicyComponent = (PolicyComponent) policyComponents
                            .next();
                    if (currentPolicyComponent instanceof Policy) {
                        policyId = ((Policy) currentPolicyComponent).getId();
                    } else if (currentPolicyComponent instanceof PolicyReference) {
                        policyId = ((PolicyReference) currentPolicyComponent).getURI().substring(1);
                    } else {
                        continue;
                    }
                    if (policyId != null) {
                        // Check whether this is a security scenario
                        scenario = SecurityScenarioDatabase.getByWsuId(policyId);
                    }
                }
            }

            return scenario;
        } catch (Exception e) {
            log.error("Error while reading Security Scenario", e);
            throw new SecurityConfigException("readingSecurity", e);
        }

    }

    /**
     * If the given service is in ghost state, force the actual service deployment. This method
     * is useful when we want to make sure the actual service is in the system before we do some
     * operation on the service.
     *
     * @param serviceName - name of the service
     */
    public void forceActualServiceDeployment(String serviceName) {
        AxisService service = axisConfig.getServiceForActivation(serviceName);
        if (service == null) {
            // try to find it from the transit ghost map
            try {
                service = GhostDeployerUtils
                        .getTransitGhostServicesMap(axisConfig).get(serviceName);
            } catch (AxisFault axisFault) {
                log.error("Error while reading Transit Ghosts map", axisFault);
            }
        }
        if (service != null && GhostDeployerUtils.isGhostService(service)) {
            // if the service is a ghost service, load the actual service
            try {
                GhostDeployerUtils.deployActualService(axisConfig, service);
            } catch (AxisFault axisFault) {
                log.error("Error while loading actual service from Ghost", axisFault);
            }
        }
    }


    /**
     * Returns the default "POX_ENABLED" cache
     */
    private Cache<String, String> getPOXCache() {
        CacheManager manager = Caching.getCacheManagerFactory().getCacheManager(POXSecurityHandler.POX_CACHE_MANAGER);
        Cache<String, String> cache = manager.getCache(POXSecurityHandler.POX_ENABLED);
        return cache;
    }

    private Map<String, String> getProperties(OMElement parentElement) {
        Map<String, String> properties = new HashMap<>();
        if (parentElement != null) {
            Iterator iterator = parentElement.getChildElements();
            while (iterator.hasNext()) {
                OMElement element = (OMElement) iterator.next();
                String nameAttribute = element.getAttribute(new QName(SecurityConstants.NAME_LABEL)).getAttributeValue();
                String value = element.getText();
                properties.put(nameAttribute, value);
            }
        }

        return properties;
    }

    private String getProperty(RampartConfig rampartConfig, Map<String, String> trustProperties, String propertyName) {

        String propertyValue = null;

        if (trustProperties != null) {
            trustProperties.get(propertyName);
            if (StringUtils.isNotEmpty(propertyValue)) {
                return propertyValue;
            }
        }

        if (rampartConfig != null) {
            if (rampartConfig.getEncrCryptoConfig() != null && rampartConfig.getEncrCryptoConfig()
                    .getProp().get(propertyName) != null) {
                propertyValue = rampartConfig.getEncrCryptoConfig().getProp().get(propertyName).toString();
                if (StringUtils.isNotEmpty(propertyValue)) {
                    return propertyValue;
                }
            }
            if (rampartConfig.getSigCryptoConfig() != null && rampartConfig.getSigCryptoConfig()
                    .getProp().get(propertyName) != null) {
                propertyValue = rampartConfig.getEncrCryptoConfig().getProp().get(propertyName).toString();
                if (StringUtils.isNotEmpty(propertyValue)) {
                    return propertyValue;
                }
            }
        }
        return propertyValue;
    }
}
