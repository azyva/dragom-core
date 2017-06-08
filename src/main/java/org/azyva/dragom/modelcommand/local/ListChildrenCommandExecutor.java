package org.azyva.dragom.modelcommand.local;

import java.util.ArrayList;
import java.util.List;

import org.azyva.dragom.model.ClassificationNode;
import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.Node;
import org.azyva.dragom.model.NodePath;
import org.azyva.dragom.modelcommand.ListChildrenCommand;
import org.azyva.dragom.modelcommand.ListChildrenCommandResult;
import org.azyva.dragom.modelcommand.ModelCommand;

public class ListChildrenCommandExecutor extends SpecificModelCommandExecutor {
  /**
   * Constructor.
   *
   * @param model Model.
   */
  public ListChildrenCommandExecutor(Model model) {
    super(model);
  }

  @Override
  public ListChildrenCommandResult executeCommand(ModelCommand modelCommand) {
    ListChildrenCommand listChildrenCommand;
    ListChildrenCommandResult listChildrenCommandResult;
    NodePath nodePath;
    ClassificationNode classificationNode;
    List<Node> listNode;
    List<String> listChildren;

    listChildrenCommand = (ListChildrenCommand)modelCommand;
    listChildrenCommandResult = new ListChildrenCommandResult();

    nodePath = listChildrenCommand.getNodePath();

    if (!nodePath.isPartial()) {
      listChildrenCommandResult.setErrorId("NODE_PATH_MUST_BE_PARTIAL");
      listChildrenCommandResult.setErrorMsg("NodePath " + nodePath + " is partial and must reference a ClassificationNode, but the node referenced is not. Remove the trailing '/' to make the NodePath complete.");

      return listChildrenCommandResult;
    }

    classificationNode = this.model.getClassificationNode(nodePath);

    if (classificationNode == null) {
      listChildrenCommandResult.setErrorId("NODE_NODE_FOUND");
      listChildrenCommandResult.setErrorMsg("No ClassificationNode corresponds to NodePath " + nodePath);

      return listChildrenCommandResult;
    }

    listNode = classificationNode.getListChildNode();
    listChildren = new ArrayList<String>();

    for (Node node: listNode) {
      listChildren.add(node.getName());
    }

    listChildrenCommandResult.setListChildren(listChildren);

    return listChildrenCommandResult;
  }
}
