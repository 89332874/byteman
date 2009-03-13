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

import org.jboss.jbossts.orchestration.rule.binding.Binding;
import org.jboss.jbossts.orchestration.rule.type.Type;
import org.jboss.jbossts.orchestration.rule.type.TypeGroup;
import org.jboss.jbossts.orchestration.rule.exception.TypeException;
import org.jboss.jbossts.orchestration.rule.exception.ExecuteException;
import org.jboss.jbossts.orchestration.rule.Rule;
import org.jboss.jbossts.orchestration.rule.helper.HelperAdapter;
import org.jboss.jbossts.orchestration.rule.grammar.ParseNode;

import java.io.StringWriter;
import java.lang.reflect.Field;

/**
 * an expression which identifies an instance field reference
 */
public class FieldExpression extends Expression
{
    public FieldExpression(Rule rule, Type type, ParseNode fieldTree, String fieldName, Expression owner, String[] pathList) {
        // we cannot process the pathlist until typecheck time
        super(rule, type, fieldTree);
        this.fieldName = fieldName;
        this.owner = owner;
        this.pathList = pathList;
        this.ownerType = null;
        this.indirectStatic = true;
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

        if (owner != null) {
            // ensure the owner is bound
            owner.bind();
        } else {
            // see if the path starts with a bound variable and, if so, treat the path as a series
            // of field references and construct a owner expression from it. if not we will have to
            // wait until runtime in order to resolve this as a static field reference
            String leading = pathList[0];
            Binding binding = getBindings().lookup(leading);
            if (binding != null) {
                // create a sequence of field expressions and make it the owner

                int l = pathList.length;
                Expression owner =  new Variable(rule, binding.getType(), token, binding.getName());
                for (int idx = 1; idx < l; idx++) {
                    owner = new FieldExpression(rule, Type.UNDEFINED, token, pathList[idx], owner, null);
                }
                this.owner = owner;
                this.pathList = null;
                // not strictly necessary?
                this.owner.bind();
            }
        }

        return true;
    }

    public Type typeCheck(Type expected) throws TypeException {
        if (owner == null && pathList != null) {
            // factor off a typename from the path
            TypeGroup typeGroup = getTypeGroup();
            Type rootType = typeGroup.match(pathList);
            if (rootType == null) {
                throw new TypeException("FieldExpression.typeCheck : invalid path " + getPath(pathList.length) + " to static field " + fieldName + getPos());
            }

            // find out how many of the path elements are included in the type name

            String rootTypeName = rootType.getName();

            int idx = getPathCount(rootTypeName);

            if (idx < pathList.length) {
                // create a static field reference using the type name and the first field name and wrap it with
                // enough field references to use up all the path
                String fieldName = pathList[idx++];
                Expression owner = new StaticExpression(rule, Type.UNDEFINED, token, fieldName, rootTypeName);
                while (idx < pathList.length) {
                    owner = new FieldExpression(rule, Type.UNDEFINED, token, pathList[idx++], owner, null);
                }
                this.owner = owner;
            } else {
                // ok this field reference is actually a static reference -- install the one we just created as
                // owner and mark this one so it sidesteps any further requests to the owner
                this.owner = new StaticExpression(rule, Type.UNDEFINED, token, this.fieldName, rootTypeName);
                this.indirectStatic = true;
            }
            // get rid of the path list now
            this.pathList = null;
            // not strictly necessary?
            this.owner.bind();
        }

        if (indirectStatic) {
            // this is really a static field reference pointed to by owner so get it to type check
            type = ownerType = Type.dereference(owner.typeCheck(expected));
            return type;
        } else {

            // ok, type check the owner and then use it to derive the field type

            ownerType = Type.dereference(owner.typeCheck(Type.UNDEFINED));
            
            if (ownerType.isUndefined()) {
                throw new TypeException("FieldExpresssion.typeCheck : unbound owner type for field " + fieldName + getPos());
            }

            Class ownerClazz = ownerType.getTargetClass();
            Class valueClass = null;

            try {
                field  = ownerClazz.getField(fieldName);
            } catch (NoSuchFieldException e) {
                throw new TypeException("FieldExpresssion.typeCheck : invalid field reference " + fieldName + getPos());
            }

            valueClass = field.getType();
            type = getTypeGroup().ensureType(valueClass);

            if (Type.dereference(expected).isDefined() && !expected.isAssignableFrom(type)) {
                throw new TypeException("FieldExpresssion.typeCheck : invalid expected type " + expected.getName() + getPos());
            }

            return type;
        }
    }

    public Object interpret(HelperAdapter helper) throws ExecuteException
    {
        if (indirectStatic) {
            return owner.interpret(helper);
        } else {
            try {
                // TODO the reference should really be an expression?
                Object value = owner.interpret(helper);

                if (value == null) {
                    throw new ExecuteException("FieldExpression.interpret : attempted field indirection through null value " + token.getText() + getPos());
                }

                return field.get(value);
            } catch (ExecuteException e) {
                throw e;
            } catch (IllegalAccessException e) {
                throw new ExecuteException("FieldExpression.interpret : error accessing field " + fieldName + getPos(), e);
            } catch (Exception e) {
                throw new ExecuteException("FieldExpression.interpret : unexpected exception accessing field " + fieldName + getPos(), e);
            }
        }
    }

    public String getPath(int len)
    {
        StringBuffer buffer = new StringBuffer();
        buffer.append(pathList[0]);

        for (int i = 1; i < len; i++) {
            buffer.append(".");
            buffer.append(pathList[i]);
        }
        return buffer.toString();
    }

    public int getPathCount(String name)
    {
        int charMax = name.length();
        int charCount = 0;
        int dotExtra = 0;
        int idx;
        for (idx = 0; idx < pathList.length; idx++) {
            charCount += (dotExtra + pathList[idx].length());
            if (charCount > charMax) {
                break;
            }
        }
        return idx;
    }

    public void writeTo(StringWriter stringWriter) {
        // we normally have a owner expression but before binding we have a path
        if (owner != null) {
            owner.writeTo(stringWriter);
        } else {
            String sepr = "";
            for (String field : pathList) {
                stringWriter.write(sepr);
                stringWriter.write(field);
                sepr =".";
            }
        }
        stringWriter.write(".");
        stringWriter.write(fieldName);
    }

    private Expression owner;
    private String[] pathList;
    private String fieldName;
    private Type ownerType;
    private Field field;
    private boolean indirectStatic;
}