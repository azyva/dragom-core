package org.azyva.dragom.modelcommand.local;

import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.modelcommand.ModelCommandExecutor;

public class LocalModelCommandExecutorFactory {
  public static ModelCommandExecutor getService() {
    return new LocalModelCommandExecutor(ExecContextHolder.get().getModel());
  }
}
