/*******************************************************************************
 * Copyright (c) 2021 Fabrice TIERCELIN and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Fabrice TIERCELIN - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.corext.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.Statement;

import org.eclipse.jdt.internal.corext.dom.ASTNodes;
import org.eclipse.jdt.internal.corext.dom.AbortSearchException;

/**
 * Implementation of the control workflow builder.
 *
 * The aim is to match a conditional part of code like:
 *
 * <pre>
 * if (c1) {
 *   if (c2) {
 *     return A;
 *   } else {
 *     return B;
 *   }
 * } else {
 *   if (c3) {
 *     return C;
 *   } else {
 *     return D;
 *   }
 * }
 * </pre>
 *
 * Any cases should be handled (no remaining cases). It is expressed this way
 * <ul>
 * <li>One case (c1, c2) goes to A</li>
 * <li>One case (c1, not c2) goes to B</li>
 * <li>One case (not c1, c3) goes to C</li>
 * <li>One case (not c1, not c3) goes to D</li>
 * </ul>
 *
 * It can handle as many conditions as you want.
 * The conditions and the expressions are analyzed as ordinary matcher using <code>org.eclipse.jdt.internal.corext.dom.ASTSemanticMatcher</code>.
 * All the remaining part of the structure may have lots of different forms:
 *
 * <pre>
 * if (c1 && c2) {
 *     return A;
 * } else if (c1 && not c2) {
 *     return B;
 * } else if (not c1 && not c3) {
 *     return D;
 * } else if (not c1 && c3) {
 *     return C;
 * }
 * </pre>
 *
 * <pre>
 * if (c1) {
 *   if (c2) {
 *     return A;
 *   }
 *   return B;
 * } else {
 *   if (c3) {
 *     return C;
 *   }
 *   return D;
 * }
 * </pre>
 *
 * <pre>
 * if (c1) {
 *   if (not c2) {
 *     return B;
 *   } else {
 *     return A;
 *   }
 * } else {
 *   if (not c3) {
 *     return D;
 *   } else {
 *     return C;
 *   }
 * }
 * </pre>
 *
 * <pre>
 * if (c1) {
 *   return c2 ? A : B;
 * } else {
 *   return c3 ? C :D;
 * }
 *
 * if (not c1) {
 *   return c3 ? C :D;
 * }
 *
 * return not c2 ? A : B;
 * </pre>
 */
public final class ControlWorkflowMatcher implements ControlWorkflowMatcherCompletable, ControlWorkflowMatcherRunnable {
	/**
	 * Id generator.
	 */
	private int idGenerator= 1;

	/**
	 * Built for each analyzed code.
	 */
	private class ControlWorkflowNode {
		/**
		 * Unique id.
		 */
		private final int id= idGenerator++;

		/**
		 * Only finalStatement, returnedValue or condition can be not null.
		 */
		private Statement finalStatement;

		/**
		 * Only finalStatement, returnedValue or condition can be not null.
		 */
		private Expression returnedValue;

		/**
		 * Only finalStatement, returnedValue or condition can be not null.
		 */
		private Expression condition;

		/**
		 * Not null only if the condition is not null.
		 */
		private ControlWorkflowNode thenNode;

		/**
		 * Not null only if the condition is not null.
		 */
		private ControlWorkflowNode elseNode;

		public Statement getFinalStatement() {
			return finalStatement;
		}

		public void setFinalStatement(final Statement finalStatement) {
			this.finalStatement= finalStatement;
		}

		public Expression getReturnedValue() {
			return returnedValue;
		}

		public void setReturnedValue(final Expression returnedValue) {
			this.returnedValue= returnedValue;
		}

		public Expression getCondition() {
			return condition;
		}

		public void setCondition(final Expression condition) {
			this.condition= condition;
		}

		public ControlWorkflowNode getThenNode() {
			return thenNode;
		}

		public void setThenNode(final ControlWorkflowNode thenNode) {
			this.thenNode= thenNode;
		}

		public ControlWorkflowNode getElseNode() {
			return elseNode;
		}

		public void setElseNode(final ControlWorkflowNode elseNode) {
			this.elseNode= elseNode;
		}

		public int getId() {
			return id;
		}

		@Override
		public int hashCode() {
			return Objects.hash(id);
		}

		@Override
		public boolean equals(final Object obj) {
			if (this == obj) {
				return true;
			}

			if (obj == null || getClass() != obj.getClass()) {
				return false;
			}

			ControlWorkflowNode other= (ControlWorkflowNode) obj;
			return id == other.id;
		}
	}

	private int nbWorkflow;

	private List<List<NodeMatcher<Expression>>> conditionsByWorkflow= new ArrayList<>();
	private List<List<NodeMatcher<Statement>>> statementsByWorkflow= new ArrayList<>();
	private List<NodeMatcher<Expression>> returnedValuesByWorkflow= new ArrayList<>();

	private ControlWorkflowMatcher() {
		// Forbidden
	}

	/**
	 * Factory.
	 *
	 * @return an instance.
	 */
	public static ControlWorkflowMatcherRunnable createControlWorkflowMatcher() {
		return new ControlWorkflowMatcher();
	}

	@Override
	public ControlWorkflowMatcherCompletable addWorkflow(final NodeMatcher<Expression> condition) {
		nbWorkflow++;

		List<NodeMatcher<Expression>> conditions= new ArrayList<>();
		conditions.add(condition);
		conditionsByWorkflow.add(conditions);
		statementsByWorkflow.add(new ArrayList<>());
		returnedValuesByWorkflow.add(null);

		return this;
	}

	@Override
	public ControlWorkflowMatcherCompletable condition(final NodeMatcher<Expression> condition) {
		conditionsByWorkflow.get(nbWorkflow - 1).add(condition);
		return this;
	}

	@Override
	public ControlWorkflowMatcherCompletable statement(final NodeMatcher<Statement> statement) {
		statementsByWorkflow.get(nbWorkflow - 1).add(statement);
		return this;
	}

	@Override
	public ControlWorkflowMatcherRunnable returnedValue(final NodeMatcher<Expression> returnedValue) {
		returnedValuesByWorkflow.set(nbWorkflow - 1, returnedValue);
		return this;
	}

	@Override
	public boolean isMatching(final Statement statement) {
		return isMatching(Arrays.asList(statement));
	}

	@Override
	public boolean isMatching(final List<Statement> actualStatements) {
		try {
			ControlWorkflowNode actualNode= buildActualNodes(actualStatements);

			expandActualNode(actualNode);

			Set<Integer> actualLastNodes= new HashSet<>();
			collectActualLastNodes(actualNode, actualLastNodes);

			if (actualLastNodes.size() != nbWorkflow) {
				return false;
			}

			boolean isPassive= isPassive(actualNode);

			for (int i= 0; i < nbWorkflow; i++) {
				if (!doMatching(actualNode, i, actualLastNodes, isPassive)) {
					return false;
				}
			}

			return actualLastNodes.isEmpty();
		} catch (AbortSearchException e) {
			return false;
		}
	}

	private boolean doMatching(final ControlWorkflowNode actualNode, final int i, final Set<Integer> actualLastNodes, final boolean isPassive) {
		ControlWorkflowNode currentActualNode= actualNode;
		List<NodeMatcher<Expression>> remainingConditions= new ArrayList<>(conditionsByWorkflow.get(i));

		while (!remainingConditions.isEmpty()) {
			if (currentActualNode == null || currentActualNode.getCondition() == null) {
				return false;
			}

			Boolean matching= null;

			for (Iterator<NodeMatcher<Expression>> iterator= remainingConditions.iterator(); iterator.hasNext();) {
				NodeMatcher<Expression> nodeMatcher= iterator.next();
				matching= nodeMatcher.isMatching(currentActualNode.getCondition());

				if (matching != null) {
					iterator.remove();
					break;
				}

				if (!isPassive) {
					return false;
				}
			}

			if (matching == null) {
				return false;
			}

			if (matching) {
				currentActualNode= currentActualNode.getThenNode();
			} else {
				currentActualNode= currentActualNode.getElseNode();
			}
		}

		if (currentActualNode.getCondition() != null
				|| currentActualNode.getFinalStatement() != null == isEmpty(statementsByWorkflow.get(i))
				|| currentActualNode.getReturnedValue() != null ^ returnedValuesByWorkflow.get(i) != null) {
			return false;
		}

		if (!isEmpty(statementsByWorkflow.get(i))) {
			if (Boolean.TRUE.equals(statementsByWorkflow.get(i).get(0).isMatching(currentActualNode.getFinalStatement()))
					&& actualLastNodes.contains(currentActualNode.getId())) {
				actualLastNodes.remove(currentActualNode.getId());
				return true;
			}
		} else if (returnedValuesByWorkflow.get(i) != null
				&& Boolean.TRUE.equals(returnedValuesByWorkflow.get(i).isMatching(currentActualNode.getReturnedValue()))
				&& actualLastNodes.contains(currentActualNode.getId())) {
			actualLastNodes.remove(currentActualNode.getId());
			return true;
		}

		return false;
	}

	private void collectActualLastNodes(final ControlWorkflowNode actualNode, final Set<Integer> actualLastNodes) {
		if (actualNode.getCondition() == null) {
			if (actualNode.getThenNode() != null || actualNode.getElseNode() != null || (actualNode.getReturnedValue() == null && actualNode.getFinalStatement() == null)) {
				throw new AbortSearchException();
			}

			actualLastNodes.add(actualNode.getId());
		} else {
			if (actualNode.getThenNode() == null || actualNode.getElseNode() == null || actualNode.getReturnedValue() != null || actualNode.getFinalStatement() != null) {
				throw new AbortSearchException();
			}

			collectActualLastNodes(actualNode.getThenNode(), actualLastNodes);
			collectActualLastNodes(actualNode.getElseNode(), actualLastNodes);
		}
	}

	private boolean isPassive(final ControlWorkflowNode actualNode) {
		return actualNode.getCondition() == null || (ASTNodes.isPassive(actualNode.getCondition()) && isPassive(actualNode.getThenNode()) && isPassive(actualNode.getElseNode()));
	}

	private void expandActualNode(final ControlWorkflowNode actualNode) {
		if (actualNode.getCondition() == null) {
			return;
		}

		PrefixExpression prefixExpression= ASTNodes.as(actualNode.getCondition(), PrefixExpression.class);
		ConditionalExpression ternaryExpression= ASTNodes.as(actualNode.getCondition(), ConditionalExpression.class);
		InfixExpression infixExpression= ASTNodes.as(actualNode.getCondition(), InfixExpression.class);

		if (prefixExpression != null) {
			if (ASTNodes.hasOperator(prefixExpression, PrefixExpression.Operator.NOT)) {
				ControlWorkflowNode oppositeNode= actualNode.getThenNode();
				actualNode.setThenNode(actualNode.getElseNode());
				actualNode.setElseNode(oppositeNode);
				actualNode.setCondition(prefixExpression.getOperand());

				expandActualNode(actualNode);
				return;
			}
		} else if (ternaryExpression != null) {
			ControlWorkflowNode node1= new ControlWorkflowNode();
			node1.setCondition(ternaryExpression.getThenExpression());
			node1.setThenNode(cloneNode(actualNode.getThenNode()));
			node1.setElseNode(cloneNode(actualNode.getElseNode()));

			ControlWorkflowNode node2= new ControlWorkflowNode();
			node2.setCondition(ternaryExpression.getElseExpression());
			node2.setThenNode(cloneNode(actualNode.getThenNode()));
			node2.setElseNode(cloneNode(actualNode.getElseNode()));

			actualNode.setCondition(ternaryExpression.getExpression());
			actualNode.setThenNode(node1);
			actualNode.setElseNode(node2);

			expandActualNode(actualNode);
			return;
		} else if (infixExpression != null && ASTNodes.hasOperator(infixExpression, InfixExpression.Operator.CONDITIONAL_AND, InfixExpression.Operator.AND, InfixExpression.Operator.CONDITIONAL_OR, InfixExpression.Operator.OR)) {
			List<Expression> allOperands= ASTNodes.allOperands(infixExpression);
			Expression firstOperand= allOperands.remove(0);
			ControlWorkflowNode currentNode= actualNode;

			for (Expression operand : allOperands) {
				ControlWorkflowNode subNode= new ControlWorkflowNode();
				subNode.setCondition(operand);
				subNode.setThenNode(cloneNode(currentNode.getThenNode()));
				subNode.setElseNode(cloneNode(currentNode.getElseNode()));
				currentNode.setCondition(firstOperand);

				if (ASTNodes.hasOperator(infixExpression, InfixExpression.Operator.CONDITIONAL_AND, InfixExpression.Operator.AND)) {
					currentNode.setThenNode(subNode);
				} else {
					currentNode.setElseNode(subNode);
				}

				currentNode= subNode;
			}

			expandActualNode(actualNode);
			return;
		} else if (infixExpression != null && !infixExpression.hasExtendedOperands() && ASTNodes.hasOperator(infixExpression, InfixExpression.Operator.XOR)) {
			ControlWorkflowNode subNode1= new ControlWorkflowNode();
			subNode1.setCondition(infixExpression.getRightOperand());
			subNode1.setThenNode(cloneNode(actualNode.getElseNode()));
			subNode1.setElseNode(cloneNode(actualNode.getThenNode()));

			ControlWorkflowNode subNode2= new ControlWorkflowNode();
			subNode2.setCondition(infixExpression.getRightOperand());
			subNode2.setThenNode(cloneNode(actualNode.getThenNode()));
			subNode2.setElseNode(cloneNode(actualNode.getElseNode()));

			actualNode.setCondition(infixExpression.getLeftOperand());
			actualNode.setThenNode(subNode1);
			actualNode.setElseNode(subNode2);

			expandActualNode(actualNode);
			return;
		}

		expandActualNode(actualNode.getThenNode());
		expandActualNode(actualNode.getElseNode());
	}

	private ControlWorkflowNode cloneNode(final ControlWorkflowNode actualNode) {
		if (actualNode == null) {
			return null;
		}

		ControlWorkflowNode clone= new ControlWorkflowNode();
		clone.setCondition(actualNode.getCondition());
		clone.setReturnedValue(actualNode.getReturnedValue());
		clone.setFinalStatement(actualNode.getFinalStatement());
		clone.setThenNode(cloneNode(actualNode.getThenNode()));
		clone.setElseNode(cloneNode(actualNode.getElseNode()));

		return clone;
	}

	private ControlWorkflowNode buildActualNodes(final Statement actualStatement) {
		if (actualStatement == null) {
			throw new AbortSearchException();
		}

		return buildActualNodes(ASTNodes.asList(actualStatement));
	}

	private ControlWorkflowNode buildActualNodes(final List<Statement> actualStatements) {
		if (isEmpty(actualStatements)) {
			throw new AbortSearchException();
		}

		IfStatement ifStatement= ASTNodes.as(actualStatements.get(0), IfStatement.class);
		ReturnStatement returnStatement= ASTNodes.as(actualStatements.get(0), ReturnStatement.class);

		if (ifStatement != null) {
			ControlWorkflowNode node= new ControlWorkflowNode();
			node.setCondition(ifStatement.getExpression());
			node.setThenNode(buildActualNodes(ifStatement.getThenStatement()));

			if (ifStatement.getElseStatement() != null && actualStatements.size() == 1) {
				node.setElseNode(buildActualNodes(ifStatement.getElseStatement()));
				return node;
			}

			if (ifStatement.getElseStatement() != null || actualStatements.size() == 1 || !ASTNodes.fallsThrough(ifStatement.getThenStatement())) {
				throw new AbortSearchException();
			}

			List<Statement> elseStmts= new ArrayList<>(actualStatements);
			elseStmts.remove(0);
			node.setElseNode(buildActualNodes(elseStmts));
			return node;
		}

		if (returnStatement != null) {
			Expression condition= returnStatement.getExpression();
			return buildActualNodes(condition);
		}

		if (actualStatements.size() == 1) {
			ControlWorkflowNode node= new ControlWorkflowNode();
			node.setFinalStatement(actualStatements.get(0));
			return node;
		}

		throw new AbortSearchException();
	}

	private ControlWorkflowNode buildActualNodes(final Expression condition) {
		ControlWorkflowNode actualNode= new ControlWorkflowNode();
		ConditionalExpression ternaryExpression= ASTNodes.as(condition, ConditionalExpression.class);

		if (ternaryExpression != null) {
			actualNode.setCondition(ternaryExpression.getExpression());
			actualNode.setThenNode(buildActualNodes(ternaryExpression.getThenExpression()));
			actualNode.setElseNode(buildActualNodes(ternaryExpression.getElseExpression()));
		} else {
			actualNode.setReturnedValue(condition);
		}

		return actualNode;
	}

	private static  boolean isEmpty(final Collection<?> collection) {
		return collection == null || collection.isEmpty();
	}
}
