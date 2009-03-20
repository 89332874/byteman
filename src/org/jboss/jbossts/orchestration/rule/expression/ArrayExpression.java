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

import org.jboss.jbossts.orchestration.rule.type.Type;
import org.jboss.jbossts.orchestration.rule.exception.TypeException;
import org.jboss.jbossts.orchestration.rule.exception.ExecuteException;
import org.jboss.jbossts.orchestration.rule.exception.CompileException;
import org.jboss.jbossts.orchestration.rule.Rule;
import org.jboss.jbossts.orchestration.rule.compiler.StackHeights;
import org.jboss.jbossts.orchestration.rule.helper.HelperAdapter;
import org.jboss.jbossts.orchestration.rule.grammar.ParseNode;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;
import java.util.Iterator;
import java.io.StringWriter;
import java.lang.reflect.Array;

/**
 * an expression which identifies an array reference.
 */

public class ArrayExpression extends Expression
{

    public ArrayExpression(Rule rule, Type type, ParseNode token, Expression arrayRef, List<Expression> idxList)
    {
        super(rule, type, token);
        this.arrayRef = arrayRef;
        this.idxList = idxList;
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
        // we  have to make sure that any names occuring in the array reference are bound
        // and that the index expressions contain valid bindings
        boolean valid = true;

        valid = arrayRef.bind();

        Iterator<Expression> iterator = idxList.iterator();

        while (iterator.hasNext()) {
            valid &= iterator.next().bind();
        }

        return valid;
    }

    public Type typeCheck(Type expected) throws TypeException {
        Type arrayType = arrayRef.typeCheck(Type.UNDEFINED);
        Type nextType = arrayType;
        for (Expression expr : idxList) {
            if (!nextType.isArray()) {
                throw new TypeException("ArrayExpression.typeCheck : invalid type for array dereference " + nextType.getName() + getPos());
            }
            nextType = nextType.getBaseType();
            expr.typeCheck(Type.N);
        }
        type = nextType;
        if (Type.dereference(expected).isDefined() && !expected.isAssignableFrom(type)) {
            throw new TypeException("ArrayExpression.typeCheck : invalid expected result type " + expected.getName() + getPos());
        }

        return type;
    }

    public Object interpret(HelperAdapter helper) throws ExecuteException {
        // evaluate the array expression then evaluate each index expression in turn and
        // dereference to access the array element

        try {
            Object value = arrayRef.interpret(helper);
            Type nextType = arrayRef.getType();
            for (Expression expr : idxList) {
                int idx = ((Number) expr.interpret(helper)).intValue();
                if (value == null) {
                    throw new ExecuteException("ArrayExpression.interpret : attempted array indirection through null value " + arrayRef.token.getText() + getPos());
                }
                value = Array.get(value, idx);
                nextType = nextType.getBaseType();
            }

            return value;
        } catch (ExecuteException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new ExecuteException("ArrayExpression.interpret : failed to evaluate expression " + arrayRef.token.getText() + getPos(), e);
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new ExecuteException("ArrayExpression.interpret : invalid index for array " + arrayRef.token.getText() + getPos(), e);
        } catch (ClassCastException e) {
            throw new ExecuteException("ArrayExpression.interpret : invalid index dereferencing array " + arrayRef.token.getText() + getPos(), e);
        } catch (Exception e) {
            throw new ExecuteException("ArrayExpression.interpret : unexpected exception dereferencing array " + arrayRef.token.getText() + getPos(), e);
        }
    }

    public void compile(MethodVisitor mv, StackHeights currentStackHeights, StackHeights maxStackHeights) throws CompileException
    {
        Type valueType = arrayRef.getType().getBaseType();
        int currentStack = currentStackHeights.stackCount;
        int expected = 0;

        // compile load of array reference -- adds 1 to stack height
        arrayRef.compile(mv, currentStackHeights, maxStackHeights);
        // for each index expression compile the expression and the do an array load
        Iterator<Expression> iterator = idxList.iterator();

        while (iterator.hasNext()) {
            Expression idxExpr = iterator.next();
            // compile expression index -- adds 1 to height
            idxExpr.compile(mv, currentStackHeights, maxStackHeights);
            // make sure the index is an integer
            compileTypeConversion(idxExpr.getType(), Type.I, mv, currentStackHeights, maxStackHeights);

            if (valueType.isObject()) {
                // compile load object - pops 2 and adds 1
                mv.visitInsn(Opcodes.AALOAD);
                expected = 1;
            } else if (valueType == Type.Z || valueType == Type.B) {
                // compile load byte - pops 2 and adds 1
                mv.visitInsn(Opcodes.BALOAD);
                expected = 1;
            } else if (valueType == Type.S) {
                // compile load short - pops 2 and adds 1
                mv.visitInsn(Opcodes.SALOAD);
                expected = 1;
            } else if (valueType == Type.C) {
                // compile load char - pops 2 and adds 1
                mv.visitInsn(Opcodes.CALOAD);
                expected = 1;
            } else if (valueType == Type.I) {
                // compile load int - pops 2 and adds 1
                mv.visitInsn(Opcodes.IALOAD);
                expected = 1;
            } else if (valueType == Type.J) {
                // compile load long - pops 2 and adds 2
                mv.visitInsn(Opcodes.LALOAD);
                expected = 2;
            } else if (valueType == Type.F) {
                // compile load float - pops 2 and adds 1
                mv.visitInsn(Opcodes.FALOAD);
                expected = 1;
            } else if (valueType == Type.D) {
                // compile load double - pops 2 and adds 2
                mv.visitInsn(Opcodes.DALOAD);
                expected = 2;
            }
            if (iterator.hasNext()) {
                assert valueType.isArray();
                valueType =valueType.getBaseType();
            }
        }
        // the last value for expected is how many bytes extra should be on the stack
        currentStackHeights.addStackCount(expected);

        // check stack height
        if (currentStackHeights.stackCount != currentStack + expected) {
            throw new CompileException("ArrayExpression.compile : invalid stack height " + currentStackHeights.stackCount + " expecting " + currentStack + expected);
        }

        // we needed room for an aray and an index or for a one or two word result
        // but the recursive evaluations will have made sure the max stack is big enough
        // so there is no need to update the maximum stack height
    }

    public void writeTo(StringWriter stringWriter) {
        arrayRef.writeTo(stringWriter);
        for (Expression expr : idxList) {
            stringWriter.write("[");
            expr.writeTo(stringWriter);
            stringWriter.write("]");
        }
    }

    Expression arrayRef;
    List<Expression> idxList;
}
