/*
* JBoss, Home of Professional Open Source
* Copyright 2008, Red Hat Middleware LLC, and individual contributors
* by the @authors tag. See the copyright.txt in the distribution for a
* full listing of individual contributors.
*
* This is free software; you can redistribute it and/or modify it
* under the terms of the GNU Lesser General Public License as
* published by the Free Software Foundation; either version 2.1 of
* the License, or (at your option) any later version.
*
* This software is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
* Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public
* License along with this software; if not, write to the Free
* Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
* 02110-1301 USA, or see the FSF site: http://www.fsf.org.
*
* @authors Andrew Dinn
*/
package org.jboss.jbossts.orchestration.rule.expression;

import org.jboss.jbossts.orchestration.rule.binding.Bindings;
import org.jboss.jbossts.orchestration.rule.grammar.ParseNode;
import static org.jboss.jbossts.orchestration.rule.grammar.ParseNode.*;
import org.jboss.jbossts.orchestration.rule.type.Type;
import org.jboss.jbossts.orchestration.rule.exception.TypeException;
import org.jboss.jbossts.orchestration.rule.Rule;

import java.util.List;
import java.util.ArrayList;

/**
 * helper class to transform parsed expression AST into an actual Expression instance
 */
public class ExpressionHelper
{
    public static Expression createExpression(Rule rule, Bindings bindings, ParseNode exprTree)
            throws TypeException
    {
        return createExpression(rule, bindings, exprTree, Type.UNDEFINED);
    }

    public static Expression createExpression(Rule rule, Bindings bindings, ParseNode exprTree, Type type)
            throws TypeException
    {
        // we expect expr =  (RETURN)
        //                   (RETURN expr)
        //                   (THROW)
        //                   (THROW expr)
        //                   (UNARYOP unary_oper expr) |
        //                   (BINOP infix_oper expr exp) |
        //                   (TERNOP simple_expr expr expr)
        //                   (ARRAY expr expr_list)
        //                   (FIELD expr simple_name)
        //                   (METH simple_name expr expr_list)
        //                   (BOOLEAN_LITERAL)
        //                   (INTEGER_LITERAL)
        //                   (FLOAT_LITERAL)
        //                   (STRING_LITERAL)
        //                   (PATH simple_name)
        //                   (PATH simple_name path)
        //                   (IDENTIFIER simple_name)
        //                   (IDENTIFIER simple_name path)

        int tag = exprTree.getTag();
        Expression expr;
        switch (tag) {
            case IDENTIFIER:
            {
                // check for path qualifier

                String name = (String)exprTree.getChild(0);
                ParseNode child1 = (ParseNode)exprTree.getChild(1);

                if (child1 == null && bindings.lookup(name) != null) {
                    // a clear cut direct variable reference
                    expr = new Variable(rule, type, exprTree);
                } else {
                    // we should only get these as identifiers for binding types or throw types which are
                    // explicitly caught by the bindings or throw processing case handlers so this is an error

                    throw new TypeException("ExpressionHelper.createExpression : unexpected occurence of IDENTIFIER in parse tree "  + exprTree.getText() + exprTree.getPos());
                }
            }
            break;
            case ARRAY:
            {
                ParseNode child0 = (ParseNode) exprTree.getChild(0);
                ParseNode child1 = (ParseNode) exprTree.getChild(1);

                Expression arrayRef = createExpression(rule, bindings, child0, Type.UNDEFINED);

                List<Expression> indices = createExpressionList(rule, bindings, child1, Type.INTEGER);

                if (indices != null) {
                    expr = new ArrayExpression(rule, type, exprTree, arrayRef, indices);
                } else {
                    throw new TypeException("ExpressionHelper.createExpression : invalid array index expression " + exprTree.getPos());
                }
            }
            break;
            case FIELD:
            {
                ParseNode child0 = (ParseNode) exprTree.getChild(0);
                ParseNode child1 = (ParseNode) exprTree.getChild(1);
                expr = createFieldExpression(rule, bindings, child0, child1, type);
            }
            break;
            case METH:
            {
                ParseNode child0 = (ParseNode) exprTree.getChild(0);
                ParseNode child1 = (ParseNode) exprTree.getChild(1);
                ParseNode child2 = (ParseNode) exprTree.getChild(2);
                int tag0 = child0.getTag();
                if (tag0 != IDENTIFIER) {
                    // uurgh we expected a method name
                    throw new TypeException("ExpressionHelper.createExpression : invalid unexpected type tag for method selector " + tag + " for expression " + child0.getText() + child0.getPos());
                }

                expr = createCallExpression(rule, bindings, child0, child1, child2, type);
            }
            break;
            case THROW:
            {
                ParseNode child0 = (ParseNode) exprTree.getChild(0);
                ParseNode child1 = (ParseNode) exprTree.getChild(1);
                int tag0 = child0.getTag();

                if (tag0 != IDENTIFIER) {
                    throw new TypeException("ExpressionHelper.createExpression : unexpected type tag in throw expression tree " + tag + " for expression " + child0.getText() + child0.getPos());
                } else {
                    expr = createThrowExpression(rule, bindings, child0, child1);
                }
            }
            break;
            case INTEGER_LITERAL:
            {
                expr = new NumericLiteral(rule, Type.INTEGER, exprTree);
            }
            break;
            case FLOAT_LITERAL:
            {
                expr = new NumericLiteral(rule, Type.FLOAT, exprTree);
            }
            break;
            case STRING_LITERAL:
            {
                expr = new StringLiteral(rule, exprTree);
            }
            break;
            case BOOLEAN_LITERAL:
            {
                expr = new BooleanLiteral(rule, exprTree);
            }
            break;
            case RETURN:
            {
                Expression returnValue;
                ParseNode child0 = (ParseNode) exprTree.getChild(0);
                if (child0 != null) {
                    returnValue = createExpression(rule, bindings, child0);
                } else {
                    returnValue = null;
                }
                expr = new ReturnExpression(rule, exprTree, returnValue);
            }
            break;
            case UNOP:
            {
                expr = createUnaryExpression(rule, bindings, exprTree, type);
            }
            break;
            case BINOP:
            {
                expr = createBinaryExpression(rule, bindings, exprTree, type);
            }
            break;
            case TERNOP:
            {
                expr = createTernaryExpression(rule, bindings, exprTree, type);
            }
            break;
            default:
            {
                throw new TypeException("ExpressionHelper.createExpression : unexpected type tag in expression tree " + tag + " for expression " + exprTree.getPos());
            }
        }

        Type exprType = Type.dereference(expr.getType());
        Type targetType = Type.dereference(type);
        if (exprType.isDefined() && targetType.isDefined() && !targetType.isAssignableFrom(exprType)) {
            // we already know this is an invalid type so notify an error and return null
            throw new TypeException("ExpressionHelper.createExpression : invalid expression type " + exprType.getName() + " expecting " + targetType.getName() + exprTree.getPos());
        } else if (targetType.isNumeric() && !exprType.isNumeric()) {
            // we already know this is an invalid type so notify an error and return null
            throw new TypeException("ExpressionHelper.createExpression : invalid expression type " + exprType.getName() + " expecting " + targetType.getName() + exprTree.getPos());
        }
        if (!expr.bind()) {
            throw new TypeException("ExpressionHelper.createExpression : unknown reference in expression" + exprTree.getPos());
        }

        return expr;
    }

    public static Expression createFieldExpression(Rule rule, Bindings bindings, ParseNode fieldTree, ParseNode targetTree, Type type)
            throws TypeException
    {
        Expression expr;
        Expression target;
        String[] pathList;

        // the recipient tree may be a path or some other form of expression
        // in the former case we don't construct the recipient until type check time

        if (targetTree.getTag() == PATH) {
            target = null;
            pathList = createPathList(targetTree);
        } else {
            target = createExpression(rule, bindings, targetTree, Type.UNDEFINED);
            pathList = null;
        }
        expr = new FieldExpression(rule, type, fieldTree, fieldTree.getText(), target, pathList);

        return expr;
    }

    public static Expression createCallExpression(Rule rule, Bindings bindings, ParseNode selectorTree, ParseNode recipientTree, ParseNode argTree, Type type)
            throws TypeException
    {
        Expression expr;
        Expression recipient;
        String[] pathList;
        List<Expression> args;

        // the recipient tree may be a path or some other form of expression
        // in the former case we don't construct the recipient until type check time

        if (recipientTree == null) {
            recipient = null;
            pathList = null;
        } else if (recipientTree.getTag() == PATH) {
            recipient = null;
            pathList = createPathList(recipientTree);
        } else {
            recipient = createExpression(rule, bindings, recipientTree, Type.UNDEFINED);
            pathList = null;
        }
        if (argTree == null) {
            args = new ArrayList<Expression>();
        } else {
            args = createExpressionList(rule, bindings, argTree);
        }

        expr = new MethodExpression(rule, type, selectorTree, recipient, args, pathList);

        return expr;
    }

    public static String[] createPathList(ParseNode pathTree)
    {
        int l = 0;
        ParseNode p = pathTree;

        while (p != null) {
            l++;
            p = (ParseNode)p.getChild(1);
        }

        String[] pathList = new String[l];

        p = pathTree;
        while (p != null) {
            l--;
            pathList[l] = (String)p.getText();
            p = (ParseNode)p.getChild(1);
        }

        return pathList;
    }

    public static Expression createThrowExpression(Rule rule, Bindings bindings, ParseNode typeNameTree, ParseNode argTree)
            throws TypeException
    {
        Expression expr;
        List<Expression> args;

        if (argTree == null) {
            args = new ArrayList<Expression>();
        } else {
            args = createExpressionList(rule, bindings, argTree);
        }

        expr = new ThrowExpression(rule, typeNameTree, args);

        return expr;
    }

    public static Expression createUnaryExpression(Rule rule, Bindings bindings, ParseNode exprTree, Type type)
            throws TypeException
    {
        // we expect ^(UNOP unary_oper expr)

        ParseNode child0 = (ParseNode) exprTree.getChild(0);
        ParseNode child1 = (ParseNode) exprTree.getChild(1);
        Expression expr;
        int tag = child0.getTag();

        switch (tag)
        {
            case TWIDDLE:
            {
                // the argument must be a numeric expression
                if (!type.isUndefined() && !type.isVoid() && !type.isNumeric()) {
                    throw new TypeException("ExpressionHelper.createUnaryExpression : invalid numeric expression" + exprTree.getPos());
                }
                Expression operand = createExpression(rule, bindings, child1, Type.NUMBER);
                expr = new TwiddleExpression(rule, exprTree, operand);
            }
            break;
            case NOT:
            {
                // the argument must be a boolean expression
                if (!type.isUndefined() && !type.isVoid() && !type.isBoolean()) {
                    throw new TypeException("ExpressionHelper.createUnaryExpression : invalid boolean expression" + exprTree.getPos());
                }
                Expression operand = createExpression(rule, bindings, child1, Type.BOOLEAN);
                expr = new NotExpression(rule, exprTree, operand);
            }
            break;
            case UMINUS:
            {
                // the argument must be a numeric expression
                if (!type.isUndefined() && !type.isVoid() && !type.isBoolean()) {
                    throw new TypeException("ExpressionHelper.createUnaryExpression : invalid boolean expression" + exprTree.getPos());
                }
                Expression operand = createExpression(rule, bindings, child1, Type.NUMBER);
                expr = new MinusExpression(rule, exprTree, operand);
            }
            break;
            case DOLLAR:
            {
                if (child1.getTag() == IDENTIFIER) {
                    expr = new DollarExpression(rule, type, exprTree, child1.getText());
                } else if (child1.getTag() == INTEGER_LITERAL) {
                    Integer intObject = (Integer) child1.getChild(0);
                    expr = new DollarExpression(rule, type, exprTree, intObject.intValue());
                } else {
                    throw new TypeException("ExpressionHelper.createUnaryExpression : unexpected type tag " + child1.getTag() + " for dollar expression tree " + child1.getText() + "" + child1.getPos());
                }
            }
            break;
            default:
            {
                throw new TypeException("ExpressionHelper.createUnaryExpression : unexpected type tag " + exprTree.getTag() + " for expression tree " + exprTree.getText() + "" + exprTree.getPos());
            }
        }

        return expr;
    }

    public static Expression createBinaryExpression(Rule rule, Bindings bindings, ParseNode exprTree, Type type)
            throws TypeException
    {
        // we expect ^(BINOP infix_oper simple_expr expr)

        ParseNode child0 = (ParseNode) exprTree.getChild(0);
        ParseNode child1 = (ParseNode) exprTree.getChild(1);
        ParseNode child2 = (ParseNode) exprTree.getChild(2);
        Expression expr;
        int oper = child0.getTag();

        switch (oper)
        {
            case PLUS:
            {
                // this is a special case since we may be doing String concatenation
                Expression operand1;
                Expression operand2;
                /* if (type == Type.STRING) {
                    // must be doing String concatenation
                    // TODO hmm, not sure this is right e.g. ("value " + a + b) might give "value 12" instead of "value 3"
                    operand1 = createExpression(rule, bindings, child1, Type.STRING);
                    operand2 = createExpression(rule, bindings, child2, Type.UNDEFINED);
                    expr = new StringPlusExpression(rule, exprTree, operand1,  operand2);
                } else */ if (type.isNumeric()) {
                    // must be doing arithmetic
                    operand1 = createExpression(rule, bindings, child1, Type.NUMBER);
                    operand2 = createExpression(rule, bindings, child2, Type.NUMBER);
                    int convertedOper = OperExpression.convertOper(oper);
                    expr = new ArithmeticExpression(rule, convertedOper, exprTree, operand1,  operand2);
                } else {
                    // see if the operand gives us any type info
                    operand1 = createExpression(rule, bindings, child1, Type.UNDEFINED);
                    if (operand1.getType().isNumeric()) {
                        operand2 = createExpression(rule, bindings, child2, Type.NUMBER);
                        int convertedOper = OperExpression.convertOper(oper);
                        expr = new ArithmeticExpression(rule, convertedOper, exprTree, operand1, operand2);
                    } else if (operand1.getType() == Type.STRING) {
                        operand2 = createExpression(rule, bindings, child2, Type.UNDEFINED);
                        expr = new StringPlusExpression(rule, exprTree, operand1,  operand2);
                    } else {
                        operand2 = createExpression(rule, bindings, child2, Type.UNDEFINED);
                        // create as generic plus expression which we will replace later during type
                        // checking
                        expr = new PlusExpression(rule, exprTree, operand1,  operand2);
                    }
                }
            }
            break;
            case MINUS:
            case MUL:
            case DIV:
            case MOD:
            {
                Expression operand1 = createExpression(rule, bindings, child1, Type.NUMBER);
                Expression operand2 = createExpression(rule, bindings, child2, Type.NUMBER);

                int convertedOper = OperExpression.convertOper(oper);
                expr = new ArithmeticExpression(rule, convertedOper, exprTree, operand1, operand2);
            }
            break;
            case BAND:
            case BOR:
            case BXOR:
            {
                Expression operand1 = createExpression(rule, bindings, child1, Type.NUMBER);
                Expression operand2 = createExpression(rule, bindings, child2, Type.NUMBER);

                int convertedOper = OperExpression.convertOper(oper);
                expr = new BitExpression(rule, convertedOper, exprTree, operand1, operand2);
            }
            break;
            case AND:
            case OR:
            {
                Expression operand1 = createExpression(rule, bindings, child1, Type.BOOLEAN);
                Expression operand2 = createExpression(rule, bindings, child2, Type.BOOLEAN);

                int convertedOper = OperExpression.convertOper(oper);
                expr = new LogicalExpression(rule, convertedOper, exprTree, operand1, operand2);
            }
            break;
            case EQ:
            case NE:
            case GT:
            case LT:
            case GE:
            case LE:
            {
                Expression operand1 = createExpression(rule, bindings, child1, Type.UNDEFINED);
                Expression operand2 = createExpression(rule, bindings, child2, Type.UNDEFINED);

                int convertedOper = OperExpression.convertOper(oper);
                expr = new ComparisonExpression(rule, convertedOper, exprTree, operand1, operand2);
            }
            break;
            default:
            {
                throw new TypeException("ExpressionHelper.createBinaryExpression : unexpected type tag in expression tree " + exprTree.getTag() + " for expression " + exprTree.getText() + "" + exprTree.getPos());
            }
        }

        return expr;
    }

    public static Expression createTernaryExpression(Rule rule, Bindings bindings, ParseNode exprTree, Type type)
            throws TypeException
    {
        // we expect ^(TERNOP ternary_oper simple_expr expr expr)

        ParseNode child0 = (ParseNode) exprTree.getChild(0);
        ParseNode child1 = (ParseNode) exprTree.getChild(1);
        ParseNode child2 = (ParseNode) exprTree.getChild(2);
        ParseNode child3 = (ParseNode) exprTree.getChild(3);
        Expression expr;
        int oper = child0.getTag();

        switch (oper)
        {
            case COND:
            {
                // the argument must be a numeric expression
                Expression operand1 = createExpression(rule, bindings, child1, Type.BOOLEAN);
                Expression operand2 = createExpression(rule, bindings, child2, type);
                Expression operand3 = createExpression(rule, bindings, child3, type);
                Type type2 = Type.dereference(operand2.getType());
                Type type3 = Type.dereference(operand3.getType());
                if (type2.isNumeric() || type3.isNumeric()) {
                    if (!type.isUndefined() && !type.isVoid() && !type.isNumeric()) {
                        throw new TypeException("ExpressionHelper.createUnaryExpression : invalid numeric expression" + exprTree.getPos());
                    }
                    expr = new ConditionalEvalExpression(rule, Type.promote(type2, type3),  exprTree, operand1,  operand2, operand3);
                } else if (type2.isDefined() && type3.isDefined()) {
                    // since they are not numeric we have to have the same type
                    if (type2 == type3) {
                        // use this type
                        expr = new ConditionalEvalExpression(rule, type2,  exprTree, operand1,  operand2, operand3);
                    } else {
                        // mismatched types so don't generate a result
                        throw new TypeException("ExpressionHelper.createTernaryExpression : mismatched types " + type2.getName() + " and " + type3.getName()  + " in conditional expression " + exprTree.getText() + exprTree.getPos());
                    }
                } else {
                    // have to wait for type check to resolve types
                    expr = new ConditionalEvalExpression(rule, Type.UNDEFINED,  exprTree, operand1,  operand2, operand3);
                }
            }
            break;
            default:
            {
                throw new TypeException("ExpressionHelper.createTernaryExpression : unexpected type tag in expression tree " + exprTree.getTag() + " for expression " + exprTree.getText() + "" + exprTree.getPos());
            }
        }

        return expr;
    }

    public static List<Expression> createExpressionList(Rule rule, Bindings bindings, ParseNode exprTree)
            throws TypeException
    {
        return createExpressionList(rule, bindings, exprTree, Type.UNDEFINED);

    }
    public static List<Expression> createExpressionList(Rule rule, Bindings bindings, ParseNode exprTree, Type type)
            throws TypeException
    {
        // we expect expr_list = ^(EXPR) |
        //                       ^(SEMI expr expr_list)
        //                       ^(COMMA expr expr_list)

        List<Expression> exprList = new ArrayList<Expression>();
        List<TypeException> exceptions = new ArrayList<TypeException>();

        while (exprTree != null)
        {
            try {
                switch (exprTree.getTag())
                {
                    case SEMI:
                    case COMMA:
                    {
                        ParseNode child0 = (ParseNode) exprTree.getChild(0);
                        // assign tree before we risk an exception
                        exprTree = (ParseNode) exprTree.getChild(1);
                        Expression expr = createExpression(rule, bindings, child0, type);
                        exprList.add(expr);
                    }
                    break;
                    default:
                    {
                        // assign tree before we risk an exception
                        ParseNode saveTree = exprTree;
                        exprTree = null;
                        Expression expr = createExpression(rule, bindings, saveTree, type);
                        exprList.add(expr);
                    }
                    break;
                }
            } catch (TypeException te) {
                exceptions.add(te);
            }
        }

        if (!exceptions.isEmpty()) {
            if (exceptions.size() == 1) {
                throw exceptions.get(0);
            } else {
                StringBuffer buffer = new StringBuffer();
                buffer.append("ExpressionHelper.createExpressionList : errors checking expression sequence");
                for (TypeException typeException : exceptions) {
                    buffer.append("\n");
                    buffer.append(typeException.toString());
                }
                throw new TypeException(buffer.toString());
            }
        }

        return exprList;
    }
}
