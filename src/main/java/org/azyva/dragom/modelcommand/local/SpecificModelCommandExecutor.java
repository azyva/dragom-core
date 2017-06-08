package org.azyva.dragom.modelcommand.local;

import org.azyva.dragom.model.Model;
import org.azyva.dragom.modelcommand.CommandResult;
import org.azyva.dragom.modelcommand.ModelCommand;

public abstract class SpecificModelCommandExecutor {
  /**
   * Model.
   */
  protected Model model;

  /**
   * Constructor.
   *
   * @param model Model.
   */
  public SpecificModelCommandExecutor(Model model) {
    this.model = model;
  }

  /**
   * Executes the ModelCommand
   * .
   * @param modelCommand ModelCommand.
   * @return CommandResult.
   */
  public abstract CommandResult executeCommand(ModelCommand modelCommand);
}
