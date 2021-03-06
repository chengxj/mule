/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.config.internal.dsl.model.extension.xml;

import static java.lang.String.format;
import static java.util.Collections.emptyMap;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static org.mule.runtime.api.component.AbstractComponent.ROOT_CONTAINER_NAME_KEY;
import static org.mule.runtime.api.component.Component.NS_MULE_PARSER_METADATA;
import static org.mule.runtime.api.el.BindingContextUtils.VARS;
import static org.mule.runtime.dsl.api.component.config.DefaultComponentLocation.fromSingleComponent;

import org.mule.runtime.api.component.ComponentIdentifier;
import org.mule.runtime.api.meta.model.ExtensionModel;
import org.mule.runtime.api.meta.model.config.ConfigurationModel;
import org.mule.runtime.api.meta.model.connection.ConnectionProviderModel;
import org.mule.runtime.api.meta.model.operation.OperationModel;
import org.mule.runtime.ast.api.ArtifactAst;
import org.mule.runtime.ast.api.ComponentAst;
import org.mule.runtime.config.internal.dsl.model.extension.xml.property.GlobalElementComponentModelModelProperty;
import org.mule.runtime.config.internal.dsl.model.extension.xml.property.OperationComponentModelModelProperty;
import org.mule.runtime.config.internal.model.ApplicationModel;
import org.mule.runtime.config.internal.model.ComponentModel;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.processor.Processor;
import org.mule.runtime.extension.api.property.XmlExtensionModelProperty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.xml.namespace.QName;

/**
 * A {@link MacroExpansionModuleModel} works tightly with a {@link ApplicationModel} to go over all the registered
 * {@link ExtensionModel}s that are XML based (see {@link XmlExtensionModelProperty}) looking for code to macro expand.
 * <p/>
 * For every occurrence that happens, it will expand the operations.
 * <p/>
 * This object works by handling {@link ComponentModel}s directly, consuming the {@link GlobalElementComponentModelModelProperty}
 * for the "config" elements while the {@link OperationComponentModelModelProperty} for the operations (aka: {@link Processor}s in
 * the XML file).
 *
 * @since 4.0
 */
public class MacroExpansionModuleModel {

  /**
   * literal that represents the name of the global element for any given module. If the module's name is math, then the value of
   * this field will name the global element as <math:config ../>
   */
  public static final String MODULE_CONFIG_GLOBAL_ELEMENT_NAME = "config";

  /**
   * literal that represents the name of the connection element for any given module. If the module's name is github, then the
   * value of this field will name the global element as <github:connection ../>. As an example, think of the following snippet:
   *
   * <code>
   *    <github:config configParameter="someFood" ...>
   *      <github:connection username="myUsername" .../>
   *    </github:config>
   * </code>
   */
  public static final String MODULE_CONNECTION_GLOBAL_ELEMENT_NAME = "connection";

  public static final String MODULE_OPERATION_CONFIG_REF = "config-ref";
  /**
   * Used to obtain the {@link ComponentIdentifier} element from the <module/>'s original {@ink ComponentModel} to be later added
   * in the macro expanded element (aka: <module-operation-chain ../>) so that the location set by the
   * {@link org.mule.runtime.config.internal.dsl.model.ComponentLocationVisitor} can properly set the paths for every element
   * (even the macro expanded)
   */
  public static final QName ORIGINAL_IDENTIFIER = new QName(NS_MULE_PARSER_METADATA, "ORIGINAL_IDENTIFIER");

  /**
   * Reserved prefix in a <module/> to define a reference an operation of the same module (no circular dependencies allowed)
   */
  public static final String TNS_PREFIX = "tns";

  public static final String DEFAULT_GLOBAL_ELEMENTS = "_defaultGlobalElements";

  /**
   * Used when the <module/> contains global elements without <property/>ies to be expanded, thus the macro expansion will take
   * care of the default global elements macro expanding them ONCE, and replacing the {@link #MODULE_OPERATION_CONFIG_REF} in the
   * <operation/>'s <body/> accordingly.
   */
  private static final String DEFAULT_CONFIG_GLOBAL_ELEMENT_SUFFIX = "%s-default-config-global-element-suffix";

  private final ApplicationModel applicationModel;
  private final ExtensionModel extensionModel;

  /**
   * From a mutable {@code applicationModel}, it will store it to apply changes when the {@link #expand()} method is executed.
   *
   * @param applicationModel to modify given the usages of elements that belong to the {@link ExtensionModel}s contained in the
   *        {@code extensions} map.
   * @param extensionModel the {@link ExtensionModel}s to macro expand in the parameterized {@link ApplicationModel}
   */
  MacroExpansionModuleModel(ApplicationModel applicationModel, ExtensionModel extensionModel) {
    this.applicationModel = applicationModel;
    this.extensionModel = extensionModel;
  }

  public void expand() {
    final List<ComponentModel> moduleGlobalElements = getModuleGlobalElements();
    final Set<String> moduleGlobalElementsNames =
        moduleGlobalElements.stream().map(ComponentModel::getNameAttribute).collect(toSet());
    expandOperations(moduleGlobalElementsNames);
    expandGlobalElements(moduleGlobalElements, moduleGlobalElementsNames);
  }

  private void expandOperations(Set<String> moduleGlobalElementsNames) {
    applicationModel.topLevelComponentsStream()
        .forEach(rootElement -> rootElement.recursiveStream()
            .filter(operationRefModel -> operationRefModel.getIdentifier().getNamespace()
                .equals(extensionModel.getXmlDslModel().getPrefix()))
            .forEach(operationRefModel -> {
              operationRefModel.getModel(OperationModel.class)
                  .ifPresent(operationModel -> {
                    final ComponentModel moduleOperationChain =
                        createModuleOperationChain((ComponentModel) operationRefModel, operationModel, moduleGlobalElementsNames,
                                                   empty(), rootElement.getComponentId().orElse(null));

                    moduleOperationChain.getInnerComponents()
                        .forEach(inner -> inner.setParent((ComponentModel) operationRefModel));
                    ((ComponentModel) operationRefModel).getInnerComponents().addAll(moduleOperationChain.getInnerComponents());
                  });
            }));
  }

  /**
   * Returns the rootest flow/subflow/munit:test/etc.'s name for the module chain that will be macro expanded. By default, it will
   * assume it's a flow or even an already macro expanded element, but if not it will ask for the parent component model, making
   * any scope (such as foreach, async, etc.) look for the flow in which is contained.
   *
   * @param rootElement
   */
  private String calculateContainerRootName(ComponentAst rootElement) {
    return rootElement.getComponentId().orElse(null);
  }

  private Optional<String> defaultGlobalElementName() {
    Optional<String> defaultElementName = empty();
    if (extensionModel.getConfigurationModels().isEmpty()
        && extensionModel.getModelProperty(GlobalElementComponentModelModelProperty.class).isPresent()) {
      defaultElementName = extensionModel.getModelProperty(GlobalElementComponentModelModelProperty.class)
          .map(globalElementComponentModelModelProperty -> format(DEFAULT_CONFIG_GLOBAL_ELEMENT_SUFFIX,
                                                                  extensionModel.getName()));
    }
    return defaultElementName;
  }

  private void expandGlobalElements(List<ComponentModel> moduleComponentModels, Set<String> moduleGlobalElementsNames) {
    if (defaultGlobalElementName().isPresent()) {
      addDefaultGlobalElements(moduleGlobalElementsNames);
    } else {
      macroExpandGlobalElements(moduleComponentModels, moduleGlobalElementsNames);
    }
  }

  private void addDefaultGlobalElements(Set<String> moduleGlobalElementsNames) {
    // scenario where it will macro expand the default elements of a <module/>
    String defaultGlobalElementSuffix = defaultGlobalElementName().get();
    List<ComponentModel> globalElements =
        extensionModel.getModelProperty(GlobalElementComponentModelModelProperty.class).get().getGlobalElements();

    ComponentModel configRefModel = new ComponentModel.Builder()
        .setIdentifier(ComponentIdentifier.builder()
            .namespaceUri(extensionModel.getXmlDslModel().getNamespace())
            .namespace(extensionModel.getXmlDslModel().getPrefix())
            .name(DEFAULT_GLOBAL_ELEMENTS).build())
        .build();
    configRefModel.setComponentLocation(fromSingleComponent(DEFAULT_GLOBAL_ELEMENTS));

    globalElements.forEach(globalElementComponenModel -> {
      final ComponentModel macroExpandedImplicitGlobalElement =
          copyGlobalElementComponentModel(globalElementComponenModel, defaultGlobalElementSuffix, moduleGlobalElementsNames,
                                          new HashMap<>());

      configRefModel.getInnerComponents().add(macroExpandedImplicitGlobalElement);
    });

    applicationModel.addRootComponentModel(configRefModel);
  }

  private void macroExpandGlobalElements(List<ComponentModel> moduleComponentModels, Set<String> moduleGlobalElementsNames) {
    // scenario where it will macro expand as many times as needed all the references of the smart connector configurations
    applicationModel.recursiveStream()
        .filter(configRefModel -> configRefModel.getIdentifier().getNamespace()
            .equals(extensionModel.getXmlDslModel().getPrefix()))
        .forEach(configRefModel -> {
          configRefModel.getModel(ConfigurationModel.class)
              .ifPresent(configurationModel -> {
                Map<String, String> propertiesMap = configRefModel.getParameters()
                    .stream()
                    .collect(toMap(paramAst -> paramAst.getModel().getName(),
                                   paramAst -> paramAst.getValue().reduce(v -> v.toString(), v -> v.toString())));
                Map<String, String> connectionPropertiesMap =
                    extractConnectionProperties(configRefModel, configurationModel);
                propertiesMap.putAll(connectionPropertiesMap);
                final Map<String, String> literalsParameters = getLiteralParameters(propertiesMap, emptyMap());
                List<ComponentModel> replacementGlobalElements =
                    createGlobalElementsInstance((ComponentModel) configRefModel, moduleComponentModels,
                                                 moduleGlobalElementsNames,
                                                 literalsParameters);
                ((ComponentModel) configRefModel).getInnerComponents().clear();
                ((ComponentModel) configRefModel).getInnerComponents().addAll(replacementGlobalElements);
              });
        });
  }

  private Optional<ConfigurationModel> getConfigurationModel() {
    return extensionModel.getConfigurationModel(MODULE_CONFIG_GLOBAL_ELEMENT_NAME);
  }

  private List<ComponentModel> createGlobalElementsInstance(ComponentModel configRefModel,
                                                            List<ComponentModel> moduleGlobalElements,
                                                            Set<String> moduleGlobalElementsNames,
                                                            Map<String, String> literalsParameters) {
    return moduleGlobalElements.stream()
        .map(globalElementModel -> {
          final ComponentModel macroExpandedGlobalElement =
              copyGlobalElementComponentModel(globalElementModel, configRefModel.getNameAttribute(), moduleGlobalElementsNames,
                                              literalsParameters);
          macroExpandedGlobalElement.setRoot(true);
          return macroExpandedGlobalElement;
        }).collect(toList());

  }

  private List<ComponentModel> getModuleGlobalElements() {
    return extensionModel.getModelProperty(GlobalElementComponentModelModelProperty.class)
        .map(GlobalElementComponentModelModelProperty::getGlobalElements)
        .orElse(new ArrayList<>());
  }

  /**
   * Takes a one liner call to any given message processor, expand it to creating a "module-operation-chain" scope which has the
   * set of properties, the set of parameters and the list of message processors to execute.
   *
   * @param operationRefModel message processor that will be replaced by a scope element named "module-operation-chain".
   * @param operationModel operation that provides both the <parameter/>s and content of the <body/>
   * @param moduleGlobalElementsNames collection with the global components names (such as <http:config name="a"../>, <file:config
   *        name="b"../>, <file:matcher name="c"../> and so on) that are contained within the <module/> that will be macro
   *        expanded
   * @param configRefParentTnsName parent reference to the global element if exists (it might not be global elements in the
   *        current module). Useful when replacing {@link #TNS_PREFIX} operations, as the references to the global elements will
   *        be those of the rootest element of the operations consumed by the app.
   * @param containerName name of the container that contains the operation to be macro expanded. Not null nor empty.
   * @return a new component model that represents the old placeholder but expanded with the content of the <body/>
   */
  private ComponentModel createModuleOperationChain(ComponentModel operationRefModel,
                                                    OperationModel operationModel, Set<String> moduleGlobalElementsNames,
                                                    Optional<String> configRefParentTnsName,
                                                    String containerName) {
    final OperationComponentModelModelProperty operationComponentModelModelProperty =
        operationModel.getModelProperty(OperationComponentModelModelProperty.class).get();
    final ComponentModel operationModuleComponentModel = operationComponentModelModelProperty
        .getBodyComponentModel();
    List<ComponentModel> bodyProcessors = operationModuleComponentModel.getInnerComponents();
    Optional<String> configRefName = referencesOperationsWithinModule((ComponentAst) operationRefModel)
        ? configRefParentTnsName
        : getConfigRefName(operationRefModel);
    ComponentModel.Builder processorChainBuilder = new ComponentModel.Builder();
    processorChainBuilder.setIdentifier(operationRefModel.getIdentifier());

    Map<String, String> propertiesMap = extractProperties(configRefName);
    Map<String, String> parametersMap = ((ComponentAst) operationRefModel).getParameters().stream()
        .filter(paramAst -> paramAst.getRawValue() != null)
        .collect(toMap(paramAst -> paramAst.getModel().getName(), paramAst -> paramAst.getRawValue()));
    operationRefModel.getRawParameters().forEach((paramName, paramValue) -> {
      processorChainBuilder.addParameter(paramName, paramValue, operationRefModel.isParameterValueProvidedBySchema(paramName));
    });

    for (ComponentModel paramModelChild : operationRefModel.getInnerComponents()) {
      final ComponentModel copiedParam = new ComponentModel.Builder(paramModelChild).build();
      processorChainBuilder.addChildComponentModel(copiedParam);
    }

    operationRefModel.getMetadata().getSourceCode().ifPresent(processorChainBuilder::setSourceCode);

    bodyProcessors.stream()
        .map(bodyProcessor -> lookForTNSOperation((ComponentAst) bodyProcessor)
            .map(tnsOperation -> createModuleOperationChain(bodyProcessor, tnsOperation, moduleGlobalElementsNames,
                                                            configRefName, containerName))
            .orElseGet(() -> copyOperationComponentModel(bodyProcessor, configRefName, moduleGlobalElementsNames,
                                                         getLiteralParameters(propertiesMap, parametersMap),
                                                         containerName)))
        .forEach(processorChainBuilder::addChildComponentModel);

    copyErrorMappings(operationRefModel, processorChainBuilder);

    for (Map.Entry<String, Object> customAttributeEntry : operationRefModel.getMetadata().getParserAttributes().entrySet()) {
      processorChainBuilder.addCustomAttribute(customAttributeEntry.getKey(), customAttributeEntry.getValue());
    }
    for (Map.Entry<String, String> customAttributeEntry : operationRefModel.getMetadata().getDocAttributes().entrySet()) {
      processorChainBuilder.addCustomAttribute(customAttributeEntry.getKey(), customAttributeEntry.getValue());
    }
    processorChainBuilder.addCustomAttribute(ROOT_CONTAINER_NAME_KEY, containerName);

    operationRefModel.getMetadata().getFileName().ifPresent(processorChainBuilder::setConfigFileName);
    operationRefModel.getMetadata().getStartLine().ifPresent(processorChainBuilder::setLineNumber);
    operationRefModel.getMetadata().getStartColumn().ifPresent(processorChainBuilder::setStartColumn);
    processorChainBuilder.addCustomAttribute(ORIGINAL_IDENTIFIER, operationRefModel.getIdentifier());

    ComponentModel processorChainModel = processorChainBuilder.build();
    for (ComponentModel processorChainModelChild : processorChainModel.getInnerComponents()) {
      processorChainModelChild.setParent(processorChainModel);
    }

    processorChainModel.setComponentModel(operationModel);
    processorChainModel.setComponentType(operationRefModel.getComponentType());

    return processorChainModel;
  }

  /**
   * Looks for the value of the {@link #MODULE_OPERATION_CONFIG_REF} in the current <operation/>, if not found then tries to
   * fallback to the default global element name. See {@link #defaultGlobalElementName()} method.
   *
   * @param operationRefModel <operaton/> to lookup the expected string reference, if exists.
   * @return the suffix needed to be used when macro expanding elements, or {@link Optional#empty()} otherwise.
   */
  private Optional<String> getConfigRefName(ComponentModel operationRefModel) {
    return operationRefModel.getRawParameters().containsKey(MODULE_OPERATION_CONFIG_REF)
        ? of(operationRefModel.getRawParameters().get(MODULE_OPERATION_CONFIG_REF))
        : defaultGlobalElementName();
  }

  /**
   * If the current operation contains any {@link ApplicationModel#ERROR_MAPPING} as a child, it will copy them to the macro
   * expanded <module-operation-chain/> as childs after the list of message processors.
   *
   * @param operationRefModel {@link ComponentModel} to look for the possible child elements
   *        {@link ApplicationModel#ERROR_MAPPING_IDENTIFIER}
   * @param processorChainBuilder the <module-operation-chain/> where the errors mappings will be copied to
   */
  private void copyErrorMappings(ComponentModel operationRefModel, ComponentModel.Builder processorChainBuilder) {
    operationRefModel.getInnerComponents().stream()
        .filter(componentModel -> componentModel.getIdentifier().equals(ApplicationModel.ERROR_MAPPING_IDENTIFIER))
        .forEach(errorMappingComponentModel -> processorChainBuilder
            .addChildComponentModel(copyComponentModel(errorMappingComponentModel)));
  }

  /**
   * Goes over the {@code modelToCopy} by consuming the attributes as they are.
   *
   * @param modelToCopy original source of truth that comes from the <module/>
   * @return a transformed {@link ComponentModel} from the {@code modelToCopy}, where the element's attributes has been updated
   *         accordingly (both global components updates plus the line number, and so on). If the value for some parameter
   */
  private ComponentModel copyComponentModel(ComponentModel modelToCopy) {
    ComponentModel.Builder operationReplacementModel = getComponentModelBuilderFrom(modelToCopy);
    for (Map.Entry<String, String> entry : modelToCopy.getRawParameters().entrySet()) {
      operationReplacementModel.addParameter(entry.getKey(), entry.getValue(), false);
    }
    for (ComponentModel operationChildModel : modelToCopy.getInnerComponents()) {
      operationReplacementModel.addChildComponentModel(copyComponentModel(operationChildModel));
    }
    return buildFrom(modelToCopy, operationReplacementModel);
  }

  /**
   * @param propertiesMap <property>s that are feed in the current usage of the <module/>
   * @param parametersMap <param>s that are feed in the current usage of the <module/>
   * @return a {@link Map} of <property>s and <parameter>s that could be replaced by their literal values
   */
  private Map<String, String> getLiteralParameters(Map<String, String> propertiesMap, Map<String, String> parametersMap) {
    final Map<String, String> literalsParameters = propertiesMap.entrySet().stream()
        .filter(entry -> !isExpression(entry.getValue()))
        .collect(toMap(e -> getReplaceableExpression(e.getKey(), VARS),
                       Map.Entry::getValue));

    literalsParameters.putAll(parametersMap.entrySet().stream()
        .filter(entry -> !isExpression(entry.getValue()))
        .collect(toMap(e -> getReplaceableExpression(e.getKey(), VARS),
                       Map.Entry::getValue)));
    return literalsParameters;
  }

  /**
   * Assembly an expression to validate if the macro expansion of the current <module> can be directly replaced by the literals
   * value
   *
   * @param name of the parameter (either a <property> or a <parameter>)
   * @param prefix binding to append for the expression to be replaced in the <module>'s code
   * @return the expression that access a variable through a direct binding (aka: a "static expression", as it doesn't use the
   *         {@link CoreEvent})
   */
  private String getReplaceableExpression(String name, String prefix) {
    return "#[" + prefix + "." + name + "]";
  }

  private boolean isExpression(String value) {
    return value.startsWith("#[") && value.endsWith("]");
  }

  /**
   * Extracts the properties of the current <module/> if applies (it might not have a configuration in it)
   *
   * @param configRefName current <operation/> to macro expand, from which the config-ref attribute's value will be extracted.
   * @return a map with the name and values of the <module/>'s properties.
   */
  private Map<String, String> extractProperties(Optional<String> configRefName) {
    Map<String, String> valuesMap = new HashMap<>();
    configRefName
        .filter(configParameter -> defaultGlobalElementName()
            .map(defaultGlobalElementName -> !defaultGlobalElementName.equals(configParameter)).orElse(true))
        .ifPresent(configParameter -> {
          // look for the global element which "name" attribute maps to "configParameter" value
          // or a nested element to a config that was added by the macroexpansion of another module before
          ComponentAst configRefComponentModel = ((ArtifactAst) applicationModel).recursiveStream()
              .filter(componentModel -> componentModel.getIdentifier().getNamespace()
                  .equals(extensionModel.getXmlDslModel().getPrefix()))
              .filter(componentModel -> componentModel.getModel(ConfigurationModel.class).isPresent()
                  && configParameter.equals(componentModel.getComponentId().orElse(null)))
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException(format("There's no <%s:config> named [%s] in the current mule app",
                                                                     extensionModel.getXmlDslModel().getPrefix(),
                                                                     configParameter)));
          // as configParameter != null, a ConfigurationModel must exist
          final ConfigurationModel configurationModel = getConfigurationModel().get();
          configRefComponentModel.getParameters().stream()
              .filter(paramAst -> paramAst.getRawValue() != null)
              .forEach(paramAst -> valuesMap.put(paramAst.getModel().getName(), paramAst.getRawValue()));
          valuesMap.putAll(extractConnectionProperties(configRefComponentModel, configurationModel));
        });
    return valuesMap;
  }

  /**
   * If the current {@link ExtensionModel} does have a {@link ConnectionProviderModel}, then it will check if the current XML does
   * contain a child of it under the connection name (see
   * {@link ModuleOperationMessageProcessorChain#MODULE_CONNECTION_GLOBAL_ELEMENT_NAME}.
   *
   * @param configRefComponentModel root element of the current XML config (global element of the parameterized operation)
   * @param configurationModel configuration model of the current element
   * @return a map of properties to be added in the macro expanded <operation/>
   */
  private Map<String, String> extractConnectionProperties(ComponentAst configRefComponentModel,
                                                          ConfigurationModel configurationModel) {
    return configurationModel.getConnectionProviderModel(MODULE_CONNECTION_GLOBAL_ELEMENT_NAME)
        .flatMap(connectionProviderModel -> configRefComponentModel.directChildrenStream()
            .filter(componentModel -> MODULE_CONNECTION_GLOBAL_ELEMENT_NAME
                .equals(componentModel.getIdentifier().getName()))
            .findFirst()
            .map(connectionComponentModel -> connectionComponentModel.getParameters().stream()
                .filter(paramAst -> paramAst.getRawValue() != null)
                .collect(toMap(paramAst -> paramAst.getModel().getName(),
                               paramAst -> paramAst.getRawValue()))))
        .orElse(emptyMap());
  }

  /**
   * Goes over the {@code modelToCopy} by consuming the attributes as they are, unless some of them are actually targeting a
   * global component (such as a configuration), in which it will append the {@code configRefName} to that reference, which will
   * be the definitive name once the Mule application has been completely macro expanded in the final XML configuration.
   *
   * @param modelToCopy original source of truth that comes from the <module/>
   * @param configRefName name of the configuration being used in the Mule application
   * @param moduleGlobalElementsNames names of the <module/>s global component that will be macro expanded in the Mule application
   * @param literalsParameters {@link Map} with all he <property>s and <parameter>s that were feed with a literal value in the
   *        Mule application's code.
   * @return a transformed {@link ComponentModel} from the {@code modelToCopy}, where the global element's attributes has been
   *         updated accordingly (both global components updates plus the line number, and so on). If the value for some parameter
   *         can be optimized by replacing it for the literal's value, it will be done as well using the
   *         {@code literalsParameters}
   */
  private ComponentModel copyGlobalElementComponentModel(ComponentModel modelToCopy, String configRefName,
                                                         Set<String> moduleGlobalElementsNames,
                                                         Map<String, String> literalsParameters) {
    ComponentModel.Builder globalElementReplacementModel = getComponentModelBuilderFrom(modelToCopy);

    for (Map.Entry<String, String> entry : modelToCopy.getRawParameters().entrySet()) {
      String value =
          calculateAttributeValue(configRefName, moduleGlobalElementsNames, entry.getValue());
      final String optimizedValue = literalsParameters.getOrDefault(value, value);
      globalElementReplacementModel.addParameter(entry.getKey(), optimizedValue, false);
    }
    for (ComponentModel operationChildModel : modelToCopy.getInnerComponents()) {
      globalElementReplacementModel.addChildComponentModel(copyGlobalElementComponentModel(operationChildModel,
                                                                                           configRefName,
                                                                                           moduleGlobalElementsNames,
                                                                                           literalsParameters));
    }
    return buildFrom(modelToCopy, globalElementReplacementModel);
  }

  /**
   * Goes over the {@code modelToCopy} by consuming the attributes as they are, unless some of them are actually targeting a
   * global component (such as a configuration), in which it will append the {@code configRefName} to that reference, which will
   * be the definitive name once the Mule application has been completely macro expanded in the final XML configuration.
   *
   * @param modelToCopy original <operation/> source of truth that comes from the <module/>
   * @param configRefName name of the configuration being used by the current <operation/>. If the operation is a TNS one, then it
   *        has the value of the rootest <operation/> being called from the application.
   * @param moduleGlobalElementsNames names of the <module/>s global component that will be macro expanded in the Mule application
   * @param literalsParameters {@link Map} with all he <property>s and <parameter>s that were feed with a literal value in the
   *        Mule application's code.
   * @param containerName name of the container that contains the operation to be macro expanded. Not null nor empty.
   * @return a transformed {@link ComponentModel} from the {@code modelToCopy}, where the global element's attributes has been
   *         updated accordingly (both global components updates plus the line number, and so on). If the value for some parameter
   *         can be optimized by replacing it for the literal's value, it will be done as well using the
   *         {@code literalsParameters}
   */
  private ComponentModel copyOperationComponentModel(ComponentModel modelToCopy, Optional<String> configRefName,
                                                     Set<String> moduleGlobalElementsNames,
                                                     Map<String, String> literalsParameters, String containerName) {
    ComponentModel.Builder operationReplacementModel = getComponentModelBuilderFrom(modelToCopy);
    for (Map.Entry<String, String> entry : modelToCopy.getRawParameters().entrySet()) {
      String value = configRefName
          .map(s -> calculateAttributeValue(s, moduleGlobalElementsNames, entry.getValue()))
          .orElseGet(entry::getValue);
      final String optimizedValue = literalsParameters.getOrDefault(value, value);
      operationReplacementModel.addParameter(entry.getKey(), optimizedValue, false);
    }

    modelToCopy.getInnerComponents().stream()
        .map(operationChildModel -> lookForTNSOperation((ComponentAst) operationChildModel)
            .map(tnsOperation -> createModuleOperationChain(operationChildModel, tnsOperation, moduleGlobalElementsNames,
                                                            configRefName, containerName))
            .orElseGet(() -> copyOperationComponentModel(operationChildModel, configRefName, moduleGlobalElementsNames,
                                                         literalsParameters, containerName)))
        .forEach(operationReplacementModel::addChildComponentModel);
    return buildFrom(modelToCopy, operationReplacementModel);
  }

  private ComponentModel.Builder getComponentModelBuilderFrom(ComponentModel componentModelOrigin) {
    ComponentModel.Builder operationReplacementModel = new ComponentModel.Builder();
    operationReplacementModel
        .setIdentifier(componentModelOrigin.getIdentifier())
        .setTextContent(componentModelOrigin.getTextContent());
    for (Map.Entry<String, Object> entry : componentModelOrigin.getMetadata().getParserAttributes().entrySet()) {
      operationReplacementModel.addCustomAttribute(entry.getKey(), entry.getValue());
    }
    for (Map.Entry<String, String> entry : componentModelOrigin.getMetadata().getDocAttributes().entrySet()) {
      operationReplacementModel.addCustomAttribute(entry.getKey(), entry.getValue());
    }
    return operationReplacementModel;
  }

  private ComponentModel buildFrom(ComponentModel componentModelOrigin, ComponentModel.Builder operationReplacementModel) {
    componentModelOrigin.getMetadata().getFileName().ifPresent(operationReplacementModel::setConfigFileName);
    componentModelOrigin.getMetadata().getStartLine().ifPresent(operationReplacementModel::setLineNumber);
    componentModelOrigin.getMetadata().getStartColumn().ifPresent(operationReplacementModel::setStartColumn);
    componentModelOrigin.getMetadata().getSourceCode().ifPresent(operationReplacementModel::setSourceCode);
    ComponentModel componentModel = operationReplacementModel.build();
    for (ComponentModel child : componentModel.getInnerComponents()) {
      child.setParent(componentModel);
    }
    return componentModel;
  }

  /**
   * True if an <operation/> A calls an <operation/> B defined in the same <module/> by using <tns:B .../>
   *
   * @param operationComponentModel operation that might or might not be referencing operations of the same module.
   * @return true if it's an <operation/> reference in the same <module/>, false otherwise
   */
  private boolean referencesOperationsWithinModule(ComponentAst operationComponentModel) {
    return TNS_PREFIX.equals(operationComponentModel.getIdentifier().getNamespace());
  }

  /**
   * Looks for an operation exposed in the current {@link ExtensionModel} that's being targeted by other operation through the
   * {@link #TNS_PREFIX} prefix.
   *
   * @param componentModel to check whether targets a <module/>'s operation or not.
   * @return an {@link OperationModel} if the parameterized {@code componentModel} targets an <operation/> of the same module by
   *         using the {@link #TNS_PREFIX} prefix.
   */
  private Optional<OperationModel> lookForTNSOperation(ComponentAst componentModel) {
    if (referencesOperationsWithinModule(componentModel)) {
      return componentModel.getModel(OperationModel.class);
    } else {
      return empty();
    }
  }

  // TODO MULE-9849: until there's no clear way to check against the ComponentModel using the
  // org.mule.runtime.config.dsl.processor.AbstractAttributeDefinitionVisitor.onReferenceSimpleParameter(), we workaround
  // the issue by checking every <module/>'s global element's name.
  private String calculateAttributeValue(String configRefNameToAppend, Set<String> moduleGlobalElementsNames,
                                         String originalValue) {
    String result;
    if ((moduleGlobalElementsNames.contains(originalValue))) {
      result = originalValue.concat("-").concat(configRefNameToAppend);
    } else {
      // not a global element, returning the original value.
      result = originalValue;
    }
    return result;
  }
}
