package org.azyva.dragom.model.plugin.impl;

import java.text.MessageFormat;
import java.util.EnumSet;
import java.util.Map;
import java.util.ResourceBundle;

import org.azyva.dragom.apiutil.ByReference;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.plugin.ModuleVersionMatcherPlugin;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.reference.ReferencePath;
import org.azyva.dragom.util.RuntimeExceptionUserError;
import org.azyva.dragom.util.Util;

/**
 * Matches {@link ModuleVersion}'s based on the value of a {@link Version}
 * attribute.
 *
 * @author David Raymond
 */
public class AttributeModuleVersionMatcherPluginImpl extends ModulePluginAbstractImpl implements ModuleVersionMatcherPlugin {
  /**
   * Runtime property specifying the {@link Version} attribute to match.
   */
  private static final String RUNTIME_PROPERTY_VERSION_ATTR = "MATCH_VERSION_ATTR";

  /**
   * Runtime property specifying the {@link Version} attribute value to match.
   */
  private static final String RUNTIME_PROPERTY_VERSION_ATTR_VALUE = "MATCH_VERSION_ATTR_VALUE";

  /**
   * Runtime property indicating to not skip children when there is no match.
   */
  private static final String RUNTIME_PROPERTY_IND_DO_NOT_SKIP_CHILDREN_IF_NO_MATCH = "IND_NOT_SKIP_CHILDREN_IF_NOT_MATCH";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_MATCH_VERSION_ATTR_OR_VALUE_NOT_SPECIFIED = "MATCH_VERSION_ATTR_OR_VALUE_NOT_SPECIFIED";

  /**
   * ResourceBundle specific to this class.
   */
  private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(AttributeModuleVersionMatcherPluginImpl.class.getName() + "ResourceBundle");

  /**
   * Constructor.
   *
   * @param module Module.
   */
  public AttributeModuleVersionMatcherPluginImpl(Module module) {
    super(module);
  }

  @Override
  public EnumSet<MatchFlag> matches(ReferencePath referencePath, ModuleVersion moduleVersion, ByReference<String> byReferenceMessage) {
    Module module;
    RuntimePropertiesPlugin runtimePropertiesPlugin;
    String matchVersionAttr;
    String matchVersionAttrValue;
    boolean indDoNotSkipChildrenIfNoMatch;
    ScmPlugin scmPlugin;
    Map<String, String> mapVersionAttr;
    String versionAttrValue;

    module = this.getModule();
    runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);
    matchVersionAttr = runtimePropertiesPlugin.getProperty(module, AttributeModuleVersionMatcherPluginImpl.RUNTIME_PROPERTY_VERSION_ATTR);
    matchVersionAttrValue = runtimePropertiesPlugin.getProperty(module, AttributeModuleVersionMatcherPluginImpl.RUNTIME_PROPERTY_VERSION_ATTR_VALUE);
    indDoNotSkipChildrenIfNoMatch = Util.isNotNullAndTrue(runtimePropertiesPlugin.getProperty(module, AttributeModuleVersionMatcherPluginImpl.RUNTIME_PROPERTY_IND_DO_NOT_SKIP_CHILDREN_IF_NO_MATCH));

    if ((matchVersionAttr == null) || (matchVersionAttrValue == null)) {
      throw new RuntimeExceptionUserError(MessageFormat.format(AttributeModuleVersionMatcherPluginImpl.resourceBundle.getString(AttributeModuleVersionMatcherPluginImpl.MSG_PATTERN_KEY_MATCH_VERSION_ATTR_OR_VALUE_NOT_SPECIFIED), module));
    }

    scmPlugin = module.getNodePlugin(ScmPlugin.class, null);
    mapVersionAttr = scmPlugin.getMapVersionAttr(moduleVersion.getVersion());

    versionAttrValue = mapVersionAttr.get(matchVersionAttr);

    if ((versionAttrValue == null) || (!versionAttrValue.equals(matchVersionAttrValue))) {
      return indDoNotSkipChildrenIfNoMatch ? ModuleVersionMatcherPlugin.MatchFlag.NO_MATCH_CONTINUE : ModuleVersionMatcherPlugin.MatchFlag.NO_MATCH_SKIP_CHILDREN;
    } else {
      return ModuleVersionMatcherPlugin.MatchFlag.MATCH_CONTINUE;
    }
  }
}
