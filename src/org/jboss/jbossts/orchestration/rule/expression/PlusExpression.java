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

/**
 * A plus operator expression which handles the case where we do not know the type of the first
 * operand. this expression must be replaced by an expression with a known type during type
 * checking
 */
public class PlusExpression extends BinaryOperExpression
{
    public PlusExpression(Rule rule, ParseNode token, Expression left, Expression right)
    {
        super(rule, PLUS, Type.UNDEFINED, token, left, right);
    }

    public Type typeCheck(Type expected) throws TypeException {
        // if the expected type is numeric then we know this must be an arithmetic plus
        // if it is string then this could still be an arithmetic plus so we will
        // have to rely on the type of the first argument to guide us

        Type type1 = getOperand(0).typeCheck((expected.isNumeric() ? expected : Type.UNDEFINED));
        Type type2;

        if (type1.isNumeric()) {
            type2 = getOperand(1).typeCheck(Type.N);
            type = Type.promote(type1, type2);
        } else if (type1.isString()) {
            type2 = getOperand(1).typeCheck(Type.STRING);
        } else {
            throw new TypeException("PlusExpression.typeCheck : invalid argument type " + type1.getName() + getPos());
        }

        return type;
    }

    public Object interpret(HelperAdapter helper) throws ExecuteException {
        Object value1 = getOperand(0).interpret(helper);
        Object value2 = getOperand(1).interpret(helper);
        if (type == Type.S) {
            String s1 = (String)value1;
            String s2 = (String)value2;
            return s1 + s2;
        } else {
            Number n1 = (Number)value1;
            Number n2 = (Number)value2;
            if (type == Type.B) {
                byte b1 = n1.byteValue();
                byte b2 = n2.byteValue();
                byte result = (byte)(b1 + b2);
                return new Byte(result);
            } else if (type == Type.S) {
                short s1 = n1.shortValue();
                short s2 = n2.shortValue();
                short result = (short)(s1 + s2);
                return new Short(result);
            }  else if (type == Type.I) {
                int i1 = n1.intValue();
                int i2 = n2.intValue();
                int result = (i1 + i2);
                return new Integer(result);
            } else if (type == Type.F) {
                float f1 = n1.floatValue();
                float f2 = n2.floatValue();
                float result = (f1 + f2);
                return new Float(result);
            } else if (type == Type.D) {
                double d1 = n1.doubleValue();
                double d2 = n2.doubleValue();
                double result = (d1 + d2);
                return new Double(result);
            } else { // type == Type.C
                char c1 = (char)n1.intValue();
                char c2 = (char)n2.intValue();
                char result = (char)(c1 + c2);
                return new Integer(result);
            }
        }
    }

    public void compile(MethodVisitor mv, StackHeights currentStackHeights, StackHeights maxStackHeights) throws CompileException
    {
        Expression oper0 = getOperand(0);
        Expression oper1 = getOperand(1);

        int currentStack = currentStackHeights.stackCount;
        int expected = 0;

        // compile and type convert each operand -- adds 2 or 4 depending upon type
        oper0.compile(mv, currentStackHeights, maxStackHeights);
        compileTypeConversion(oper0.getType(), type, mv, currentStackHeights, maxStackHeights);
        oper1.compile(mv, currentStackHeights, maxStackHeights);
        compileTypeConversion(oper1.getType(), type, mv, currentStackHeights, maxStackHeights);

        if (type == Type.S) {
            // ok, we could optimize this for the case where the left or right operand is a String plus expression
            // by employing a StringBuffer but for now we will just evaluate the left and right operand and
            // then call concat to join them
            // add two strings leaving one string
            expected = 1;
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;");
        } else if (type == Type.B) {
            // add two bytes leaving one byte
            expected = 1;
            mv.visitInsn(Opcodes.IADD);
            mv.visitInsn(Opcodes.I2B);
        } else if (type == Type.S ) {
            // add two shorts leaving one short
            expected = 1;
            mv.visitInsn(Opcodes.IADD);
            mv.visitInsn(Opcodes.I2S);
        } else if (type == Type.C) {
            // add two chars leaving one char
            expected = 1;
            mv.visitInsn(Opcodes.IADD);
            mv.visitInsn(Opcodes.I2C);
        } else if (type == Type.I) {
            // add two ints leaving one int
            expected = 1;
            mv.visitInsn(Opcodes.IADD);
        } else if (type == Type.J) {
            // add two longs leaving one long
            expected = 1;
            mv.visitInsn(Opcodes.LADD);
        } else if (type == Type.F) {
            // add two floats leaving one float
            expected = 1;
            mv.visitInsn(Opcodes.FADD);
        } else if (type == Type.D) {
            // add two doubles leaving one double
            expected = 1;
            mv.visitInsn(Opcodes.FADD);
        }

        if (currentStackHeights.stackCount != currentStack + expected) {
            throw new CompileException("PlusExpression.compile : invalid stack height " + currentStackHeights.stackCount + " expecting " + currentStack + expected);
        }

        // we need room for 2 * expected words at our maximum

        int overflow = (currentStack + 2 * expected) - maxStackHeights.stackCount;
        if (overflow > 0) {
            maxStackHeights.addStackCount(overflow);
        }
    }
}