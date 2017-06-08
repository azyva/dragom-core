package org.azyva.dragom.modelcommand.local;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import org.azyva.dragom.model.Model;
import org.azyva.dragom.modelcommand.CommandResult;
import org.azyva.dragom.modelcommand.ModelCommand;
import org.azyva.dragom.modelcommand.ModelCommandExecutor;
import org.azyva.dragom.util.Util;

public class LocalModelCommandExecutor implements ModelCommandExecutor {
  /**
   * Model.
   */
  private Model model;

  /**
   * Constructor.
   *
   * @param model Model.
   */
  public LocalModelCommandExecutor(Model model) {
    this.model = model;
  }

  @SuppressWarnings("unchecked")
  @Override
  public CommandResult executeCommand(ModelCommand modelCommand) {
    String commandName;
    String commandImplementationClass;
    Class<? extends SpecificModelCommandExecutor> classSpecificModelCommandExecutor;
    Constructor<? extends SpecificModelCommandExecutor> constructor;
    SpecificModelCommandExecutor specificModelCommandExecutor;

    if (modelCommand.getClass().getPackage().getName().equals("org.azyva.dragom.modelcommand")) {
      commandName = modelCommand.getClass().getSimpleName();
      commandImplementationClass = this.getClass().getPackage().getName() + '.' + commandName + "Executor";
    } else {
      Util.applyDragomSystemProperties();

      commandImplementationClass = System.getProperty("org.azyva.dragom.CommandImplementationClass." + modelCommand.getClass().getName());
    }

    try {
      classSpecificModelCommandExecutor = (Class<? extends SpecificModelCommandExecutor>) Class.forName(commandImplementationClass);
      constructor = classSpecificModelCommandExecutor.getConstructor(Model.class);
      specificModelCommandExecutor = constructor.newInstance(this.model);
    } catch (ClassNotFoundException | NoSuchMethodException | InvocationTargetException | InstantiationException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }

    return specificModelCommandExecutor.executeCommand(modelCommand);
  }

}
