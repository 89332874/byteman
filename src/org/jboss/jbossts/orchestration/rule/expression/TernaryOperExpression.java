package org.jboss.jbossts.orchestration.rule.expression;

import org.jboss.jbossts.orchestration.rule.type.Type;
import org.jboss.jbossts.orchestration.rule.binding.Bindings;
import org.jboss.jbossts.orchestration.rule.exception.TypeException;
import org.jboss.jbossts.orchestration.rule.Rule;
import org.antlr.runtime.Token;

/**
 * ternary operators includes conditional evaluation operator 'cond ? if_expr : else_expr'
 */
public abstract class TernaryOperExpression extends OperExpression
{
    public TernaryOperExpression(Rule rule, int oper, Type type, Token token, Expression operand1,
                                 Expression operand2, Expression operand3)
    {
        super(rule, oper, type, token);
        this.operand1 = operand1;
        this.operand2 = operand2;
        this.operand3 = operand3;
    }

    /**
     * verify that variables mentioned in this expression are actually available in the supplied
     * bindings list and infer/validate the type of this expression or its subexpressions
     * where possible
     *
     * @return true if all variables in this expression are bound and no type mismatches have
     *         been detected during inference/validation.
     */
    public boolean bind() {
        // we just need to ensure that the operands can find their bindings
        // run both so we get as many errors as possible

        boolean success = operand1.bind();
        success &= operand2.bind();
        success &= operand3.bind();
        return success;
    }

    /**
     * return the operand with the given index or null if the index is out of range
     * @param index
     * @return the operand with the given index
     */
    public Expression getOperand(int index)
    {
        if (index == 0) {
            return operand1;
        } else if (index == 1) {
            return operand2;
        } else if (index == 2) {
            return operand3;
        }

        return null;
    }

    private Expression operand1;
    private Expression operand2;
    private Expression operand3;
}