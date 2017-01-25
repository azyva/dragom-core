package org.azyva.dragom.model.plugin.impl;

import java.util.EnumSet;

import org.azyva.dragom.apiutil.ByReference;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.plugin.ModuleVersionMatcherPlugin;
import org.azyva.dragom.reference.ReferencePath;

/**
 * Dummy implementation of ModuleVersionMatcherPlugin which matches everything.
 *
 * <p>This is not very useful except in some situations such as where a
 * ModuleVersionMatcher can be specified ({@link RuntimeSelectionPluginFactory}
 * and we want to make it explicit that we want to match everything.
 *
 * @author David Raymond
 */
public class MatchAllModuleVersionMatcherPluginImpl extends ModulePluginAbstractImpl implements ModuleVersionMatcherPlugin {

  /**
   * Constructor.
   *
   * @param module Module.
   */
  public MatchAllModuleVersionMatcherPluginImpl(Module module) {
    super(module);
  }

  @Override
  public EnumSet<MatchFlag> matches(ReferencePath referencePath, ModuleVersion moduleVersion, ByReference<String> byReferenceMessage) {
    return MatchFlag.MATCH_CONTINUE;
  }
}
