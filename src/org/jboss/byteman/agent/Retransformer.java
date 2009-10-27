/*
* JBoss, Home of Professional Open Source
* Copyright 2008-9, Red Hat Middleware LLC, and individual contributors
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
package org.jboss.byteman.agent;

import java.lang.instrument.Instrumentation;
import java.util.*;
import java.util.jar.JarFile;
import java.io.PrintWriter;

/**
 * byte code transformer used to introduce byteman events into JBoss code
 */
public class Retransformer extends Transformer {

    /**
     * constructor allowing this transformer to be provided with access to the JVM's instrumentation
     * implementation
     *
     * @param inst the instrumentation object used to interface to the JVM
     */
    public Retransformer(Instrumentation inst, List<String> scriptPaths, List<String> scriptTexts, boolean isRedefine, String hostname, Integer port)
            throws Exception
    {
        super(inst, scriptPaths, scriptTexts, isRedefine);
        addTransformListener(hostname, port);
    }

    protected void installScript(List<String> scriptTexts, List<String> scriptNames, PrintWriter out) throws Exception
    {
        int length = scriptTexts.size();
        List<RuleScript> toBeAdded = new LinkedList<RuleScript>();
        List<RuleScript> toBeRemoved = new LinkedList<RuleScript>();

        for (int i = 0; i < length ; i++) {
            String scriptText = scriptTexts.get(i);
            String scriptName = scriptNames.get(i);

            List<RuleScript> ruleScripts = processScripts(scriptText, scriptName);
            toBeAdded.addAll(ruleScripts);
        }

        for (RuleScript ruleScript : toBeAdded) {
            String name = ruleScript.getName();
            String className = ruleScript.getTargetClass();
            String baseName = null;
            int lastDotIdx = className.lastIndexOf('.');
            if (lastDotIdx >= 0) {
                baseName = className.substring(lastDotIdx + 1);
            }

            RuleScript previous;

            previous = nameToScriptMap.get(name);
            if (previous != null) {
                out.println("redefining rule " + name);
                toBeRemoved.add(previous);
                previous.setDeleted();
            } else {
                out.println("install rule " + name);
            }
            nameToScriptMap.put(name, ruleScript);

            // remove any old scripts and install the new ones to ensure that
            // automatic loads do the right thing

            synchronized(targetToScriptMap) {
                List<RuleScript> list = targetToScriptMap.get(className);
                if (list != null) {
                    if (previous != null) {
                        list.remove(previous);
                    }
                } else {
                    list = new ArrayList<RuleScript>();
                    targetToScriptMap.put(className, list);
                }
                list.add(ruleScript);
                if (baseName != null) {
                    list = targetToScriptMap.get(baseName);
                    if (list != null) {
                        if (previous != null) {
                            list.remove(previous);
                        }
                    } else {
                        list = new ArrayList<RuleScript>();
                        targetToScriptMap.put(baseName, list);
                    }
                }
            }
        }


        // ok, now that we have updated the maps we need to find all classes which match the scripts and
        // retransform them

        // list all class names for the to be aded and to be removed scripts

        List<String> affectedClassNames = new LinkedList<String>();

        for (RuleScript ruleScript : toBeAdded) {
            String targetClassName = ruleScript.getTargetClass();
            if (!affectedClassNames.contains(targetClassName)) {
                affectedClassNames.add(targetClassName);
            }
        }

        for (RuleScript ruleScript : toBeRemoved) {
            String targetClassName = ruleScript.getTargetClass();
            if (!affectedClassNames.contains(targetClassName)) {
                affectedClassNames.add(targetClassName);
            }
        }

        // now look for loaded classes whose names are in the list

        List<Class<?>> transformed = new LinkedList<Class<?>>();

        for (Class clazz : inst.getAllLoadedClasses()) {
            String name = clazz.getName();
            int lastDot = name.lastIndexOf('.');

            if (isBytemanClass(name) || !isTransformable(name)) {
                continue;
            }

            // TODO only retransform classes for which rules have been added or removed
            if (affectedClassNames.contains(name)) {
                transformed.add(clazz);
            } else if (lastDot >= 0 && affectedClassNames.contains(name.substring(lastDot+1))) {
                transformed.add(clazz);
            }
        }

        // retransform all classes whose rules have changed

        if (!transformed.isEmpty()) {
            Class<?>[] transformedArray = new Class<?>[transformed.size()];
            inst.retransformClasses(transformed.toArray(transformedArray));
        }

        // now we can safely purge keys for all deleted scripts

        for (RuleScript ruleScript : toBeRemoved) {
            ruleScript.purge();
        }
    }


    protected void listScripts(PrintWriter out)  throws Exception
    {
        Iterator<RuleScript> iterator = nameToScriptMap.values().iterator();

        if (!iterator.hasNext()) {
            out.println("no rules installed");
        } else {
            while (iterator.hasNext()) {
                RuleScript ruleScript = iterator.next();
                ruleScript.writeTo(out);
                synchronized (ruleScript) {
                    List<Transform> transformed = ruleScript.getTransformed();
                    if (transformed != null) {
                        Iterator<Transform> iter = transformed.iterator();
                        while (iter.hasNext()) {
                            Transform transform = iter.next();
                            transform.writeTo(out);
                        }
                    }
                }
            }
        }
    }

    private void addTransformListener(String hostname, Integer port)
    {
        TransformListener.initialize(this, hostname, port);
    }

    public void removeScripts(List<String> scriptTexts, PrintWriter out) throws Exception
    {
        List<RuleScript> toBeRemoved = new LinkedList<RuleScript>();

        if (scriptTexts != null) {
            int length = scriptTexts.size();
            for (int i = 0; i < length ; i++) {
                String scriptText = scriptTexts.get(i);
                String[] lines = scriptText.split("\n");
                for (int j = 0; j < lines.length; j++) {
                    String line = lines[j].trim();
                    if (line.startsWith("RULE ")) {
                        String name = line.substring(5).trim();
                        RuleScript ruleScript = nameToScriptMap.get(name);
                        if (ruleScript ==  null) {
                            out.print("ERROR failed to find loaded rule with name ");
                            out.println(name);
                        } else if (toBeRemoved.contains(ruleScript)) {
                            out.print("WARNING duplicate occurence for rule name ");
                            out.println(name);
                        } else {
                            toBeRemoved.add(ruleScript);
                        }
                    }
                }
            }
        } else {
            toBeRemoved.addAll(nameToScriptMap.values());
        }

        if (toBeRemoved.isEmpty()) {
            out.println("ERROR No rule scripts to remove");
            return;
        }
        
        for (RuleScript ruleScript : toBeRemoved) {
            String name = ruleScript.getName();
            String targetClassName = ruleScript.getTargetClass();
            String baseName = null;
            int lastDotIdx = targetClassName.lastIndexOf('.');
            if (lastDotIdx >= 0) {
                baseName = targetClassName.substring(lastDotIdx + 1);
            }

            // update the name to script map to remove the entry for this rule

            nameToScriptMap.remove(name);
            
            // invalidate the script first then delete it from the target map
            // so it is no longer used to do any transformatuion of classes

            ruleScript.setDeleted();
            
            synchronized(targetToScriptMap) {
                List<RuleScript> list = targetToScriptMap.get(targetClassName);
                if (list != null) {
                    list.remove(ruleScript);
                    if (list.isEmpty()) {
                        targetToScriptMap.remove(targetClassName);
                    }
                }
                if (baseName != null) {
                    list = targetToScriptMap.get(baseName);
                    if (list != null) {
                        list.remove(ruleScript);
                        if (list.isEmpty()) {
                            targetToScriptMap.remove(baseName);
                        }
                    }
                }
            }
        }

        // ok, now that we have updated the maps we need to find all classes which match the scripts and
        // retransform them

        // list all class names for the to be removed scripts

        List<String> affectedClassNames = new LinkedList<String>();

        for (RuleScript ruleScript : toBeRemoved) {
            String targetClassName = ruleScript.getTargetClass();
            if (!affectedClassNames.contains(targetClassName)) {
                affectedClassNames.add(targetClassName);
            }
        }

        // now look for loaded classes whose names are in the list

        List<Class<?>> transformed = new LinkedList<Class<?>>();

        for (Class clazz : inst.getAllLoadedClasses()) {
            String name = clazz.getName();
            int lastDot = name.lastIndexOf('.');

            if (isBytemanClass(name) || !isTransformable(name)) {
                continue;
            }

            // retransform if this class has been affected by the delete

            if (affectedClassNames.contains(name)) {
                transformed.add(clazz);
            } else if (lastDot >= 0 && affectedClassNames.contains(name.substring(lastDot+1))) {
                transformed.add(clazz);
            }
        }

        // retransform all classes affected by the change

        if (!transformed.isEmpty()) {
            Class<?>[] transformedArray = new Class<?>[transformed.size()];
            inst.retransformClasses(transformed.toArray(transformedArray));
        }

        // now we can safely purge keys for all the deleted scripts -- we need to do this
        // after the retransform because the latter removes the trigger code which uses
        // the rule key

        for (RuleScript ruleScript : toBeRemoved) {
            ruleScript.purge();
            out.println("uninstall RULE " + ruleScript.getName());
        }
    }

    public void appendJarFile(PrintWriter out, JarFile jarfile, boolean isBoot) throws Exception
    {
        if (isBoot) {
            inst.appendToBootstrapClassLoaderSearch(jarfile);
            out.println("append boot jar " + jarfile.getName());
        } else {
            inst.appendToSystemClassLoaderSearch(jarfile);
            out.println("append sys jar " + jarfile.getName());
        }
    }
}
