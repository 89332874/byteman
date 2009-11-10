package org.jboss.byteman.agent;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Class to manage indexing and lookup of rule scripts by rule name and by either class or interface name
 */
public class ScriptRepository
{
    public ScriptRepository()
    {
        targetClassIndex = new HashMap<String, List<RuleScript>>();
        targetInterfaceIndex = new HashMap<String, List<RuleScript>>();
        ruleNameIndex = new HashMap<String, RuleScript>();
    }

    /**
     * Split the text of a script file into a list of individual rule scripts
     * @param scriptText the text obtained from a script file
     * @param scriptFile teh name of the file containing teh text
     * @return a list of rule scripts
     * @throws Exception if there is an error in the format of the script file tesxt
     */
    public List<RuleScript> processScripts(String scriptText, String scriptFile) throws Exception
    {
        List<RuleScript> ruleScripts = new LinkedList<RuleScript>();

        if (scriptText != null) {
            // split rules into separate lines
            String[] lines = scriptText.split("\n");
            List<String> rules = new ArrayList<String>();
            String nextRule = "";
            String sepr = "";
            String name = null;
            String targetClass = null;
            String targetMethod = null;
            String targetHelper = null;
            LocationType locationType = null;
            Location targetLocation = null;
            boolean isInterface = false;
            int lineNumber = 0;
            int startNumber = -1;
            int maxLines = lines.length;
            boolean inRule = false;
            for (String line : lines) {
                line = line.trim();
                lineNumber++;
                if (line.startsWith("#")) {
                    if (inRule) {
                        // add a blank line in place of the comment so the line numbers
                        // are reported consistently during parsing
                        nextRule += sepr;
                        sepr = "\n";
                    } // else { // just drop comment line }
                } else if (line.startsWith("RULE ")) {
                    inRule = true;
                    name = line.substring(5).trim();
                    if (name.equals("")) {
                        throw new Exception("org.jboss.byteman.agent.Transformer : RULE with no name at line " + lineNumber + " in script " + scriptFile);
                    }
                } else if (!inRule) {
                    if (!line.equals("")) {
                        throw new Exception("org.jboss.byteman.agent.Transformer : invalid text outside of RULE/ENDRULE " + "at line " + lineNumber + " in script " + scriptFile);
                    }
                } else if (line.startsWith("CLASS ")) {
                    targetClass = line.substring(6).trim();
                } else if (line.startsWith("INTERFACE ")) {
                    targetClass = line.substring(10).trim();
                    isInterface = true;
                } else if (line.startsWith("METHOD ")) {
                    targetMethod = line.substring(7).trim();
                } else if (line.startsWith("HELPER ")) {
                    targetHelper = line.substring(7).trim();
                } else if ((locationType = LocationType.type(line)) != null) {
                    String parameters = LocationType.parameterText(line);
                    targetLocation = Location.create(locationType, parameters);
                    if (targetLocation == null) {
                        throw new Exception("org.jboss.byteman.agent.Transformer : invalid target location at line " + lineNumber + " in script " + scriptFile);
                    }
                } else if (line.startsWith("ENDRULE")) {
                    if (name == null) {
                        throw new Exception("org.jboss.byteman.agent.Transformer : no matching RULE for ENDRULE at line " + lineNumber + " in script " + scriptFile);
                    } else if (targetClass == null) {
                        throw new Exception("org.jboss.byteman.agent.Transformer : no CLASS for RULE  " + name + " in script " + scriptFile);
                    } else if (targetMethod == null) {
                        throw new Exception("org.jboss.byteman.agent.Transformer : no METHOD for RULE  " + name + " in script " + scriptFile);
                    } else {
                        if (targetLocation == null) {
                            targetLocation = Location.create(LocationType.ENTRY, "");
                        }
                        ruleScripts.add(new RuleScript(name, targetClass, isInterface, targetMethod, targetHelper, targetLocation, nextRule, startNumber, scriptFile));
                    }
                    name = null;
                    targetClass = null;
                    targetMethod = null;
                    targetLocation = null;
                    nextRule = "";
                    sepr = "";
                    inRule = false;
                    // reset start nuuber so we pick up the next rule text line
                    startNumber = -1;
                } else if (lineNumber == maxLines && !nextRule.trim().equals("")) {
                    throw new Exception("org.jboss.byteman.agent.Transformer : no matching ENDRULE for RULE " + name + " in script " + scriptFile);
                } else {
                    // this is a line of rule text - see if it is the first one
                    if (startNumber < 0) {
                        startNumber = lineNumber;
                    }
                    nextRule += sepr + line;
                    sepr = "\n";
                }
            }
        }

        return ruleScripts;
    }

    /**
     * add a rule script to the repository returning any existing script with the same name or null
     * if no such script can be found. if a script is returned it will have been deactivated.
     * @param script the script to be added to the repository
     * @return any previous script with the same name or null
     */
    public RuleScript addScript(RuleScript script)
    {
        String name = script.getName();
        RuleScript previous = null;

        // insert the script by name, invalidating any old script

        synchronized (ruleNameIndex) {
            previous = ruleNameIndex.put(name, script);
            if (previous != null) {
                boolean isDeleted = previous.setDeleted();
                if (isDeleted) {
                    // it is some other thread's responsibility to remove the script
                    previous = null;
                }
            }
        }

        if (previous == null) {
            // just index the new script

            if (script.isInterface()) {
                indexTarget(script,  targetInterfaceIndex);
            } else {
                indexTarget(script, targetClassIndex);
            }
        } else {
            boolean isInterface =  script.isInterface();
            boolean wasInterface = previous.isInterface();

            if (isInterface == wasInterface) {
                // both in the same index so try a reindex
                Map<String, List<RuleScript>> index = (isInterface ? targetInterfaceIndex : targetClassIndex);
                reindexTarget(script, previous, index);
            } else if (isInterface) {
                // different indexes so unindex then index
                unindexTarget(previous, targetClassIndex);
                indexTarget(script, targetInterfaceIndex);
            } else {
                unindexTarget(previous, targetInterfaceIndex);
                indexTarget(script, targetClassIndex);
            }
        }

        return previous;
    }

    /**
     * remove a rule script from the repository returning the script if it is found or null
     * if is not found. if a script is returned it will have been deactivated.
     * @param script the script to be removed from the repository.
     * @return the script if it was found in the repository and removed successfully or null
     * if it had already been removed.
     */
    public RuleScript removeScript(RuleScript script)
    {
        String name = script.getName();
        RuleScript current;

        // check for the script by name

        synchronized (ruleNameIndex) {
            current = ruleNameIndex.get(name);
            if (current == script) {
                ruleNameIndex.remove(current);
                boolean isDeleted = current.setDeleted();
                if (isDeleted) {
                    // it is some other thread's responsibility to remove the script
                    current = null;
                }
            } else {
                // it is some other thread's responsibility to remove the script
                current = null;
            }
        }

        // if we found a script then we have to unindex it

        if (current != null) {
            Map<String, List<RuleScript>> index = (current.isInterface() ? targetInterfaceIndex : targetClassIndex);
            unindexTarget(current, index);
        }

        return current;
    }

    /**
     * remove a rule script from the repository by name returning the script if it is found or null
     * if is not found. if a script is returned it will have been deactivated.
     * @param name the name of the script to be removed from the repository
     * @return the script if it was found in the repository or null if none was found
     */
    public RuleScript removeScript(String name)
    {
        RuleScript current = scriptForRuleName(name);

        if (current != null) {
            current = removeScript(current);
        }

        return current;
    }

    /**
     * locate a rule script with a given name
     * @param name the name of the rule script
     * @return the script with that name or null if no such script can be found
     */
    public RuleScript scriptForRuleName(String name)
    {
        synchronized (ruleNameIndex) {
            return ruleNameIndex.get(name);
        }
    }

    /**
     * return a list of all class scripts indexed using the supplied name. note that if name is
     * package qualified then only scripts with the full package qualificaton will be returned
     * whereas if name is not package qualified then only scripts with the unqualified name
     * will be returned. Note that the returned list can be iterated safely but will not reflect
     * later additions to or deletions from the list.
     * @param name
     * @return
     */

    public List<RuleScript> scriptsForClassName(String name)
    {
        synchronized (targetClassIndex) {
            return targetClassIndex.get(name);
        }
    }

    /**
     * return a list of all interface scripts indexed using the supplied name. note that if name is
     * package qualified then only scripts with the full package qualificaton will be returned
     * whereas if name is not package qualified then only scripts with the unqualified name
     * will be returned. Note that the returned list can be iterated safely but will not reflect
     * later additions to or deletions from the list.
     * @param name
     * @return
     */

    public List<RuleScript> scriptsForInterfaceName(String name)
    {
        synchronized (targetInterfaceIndex) {
            return targetInterfaceIndex.get(name);
        }
    }

    /**
     * return a list containing all the currently installed rule scripts.
     * @return
     */
    public List<RuleScript> currentRules()
    {
        return new ArrayList(ruleNameIndex.values());
    }

    /**
     * insert a script into the index using the script target class name as the index key.
     * @param script
     * @param index
     */
    private void indexTarget(RuleScript script, Map<String, List<RuleScript>> index)
    {
        String key = script.getTargetClass();
        // synchronize on the new script to avoid ant race with a deleting thread
        synchronized(script) {
            if (script.isDeleted()) {
                return;
            }
            synchronized (index) {
                List<RuleScript> entry = index.get(key);
                // always create a new list so that we don't affect any in progress iteration of the previous value
                if (entry == null) {
                    entry = new ArrayList();
                    entry.add(script);
                } else {
                    entry = new ArrayList(entry);
                    entry.add(script);
                }
                index.put(key, entry);
            }
        }
    }

    /**
     * remove a script from the index using the script target class name as the index key.
     * @param script
     * @param index
     */
    private void unindexTarget(RuleScript script, Map<String, List<RuleScript>> index)
    {
        synchronized (index) {
            String key = script.getTargetClass();
            List<RuleScript> entry = index.get(key);
            // check it has not been deleted by another thread
            if (entry != null && entry.contains(script)) {
                if (entry.size() == 1) {
                    // removing the last one so reset entry to null
                    entry = null;
                } else {
                    // always create a new list so that we don't affect any in progress iteration of the previous value
                    entry = new ArrayList<RuleScript>(entry);
                    entry.remove(script);
                }
                index.put(key, entry);
            }
        }

    }

    /**
     * replace a script in the index using the script target class name as the index key.
     * @param script
     * @param index
     */
    private void reindexTarget(RuleScript script, RuleScript previous, Map<String, List<RuleScript>> index)
    {
        // synchronize on the new script to avoid ant race with a deleting thread
        synchronized (script) {
            if (script.isDeleted()) {
                // we just need to delete the old script
                unindexTarget(previous, index);
                return;
            } else {
                String key = script.getTargetClass();
                String oldKey = previous.getTargetClass();

                synchronized (index) {
                    if (key == oldKey) {
                        // both in the same list so do one update
                        List<RuleScript> entry = index.get(key);
                        // check old one  has not been deleted by another thread
                        if (entry == null || !entry.contains(previous)) {
                            // some other thread must have deleted the old one so just insert the new one

                            // always create a new list so that we don't affect any in progress iteration of the previous value
                            entry = new ArrayList<RuleScript>(entry);
                            entry.add(script);
                        } else {
                            // always create a new list so that we don't affect any in progress iteration of the previous value
                            entry = new ArrayList(entry);
                            entry.remove(previous);
                            entry.add(script);
                        }
                        index.put(key, entry);
                    } else {
                        List<RuleScript> entry = index.get(oldKey);
                        // check old one  has not been deleted by another thread
                        if (entry != null && entry.contains(previous)) {
                            // unindex the previous script
                            if (entry.size() == 1) {
                                // removing the last one so reset entry to null
                                entry = null;
                            } else {
                                // always create a new list so that we don't affect any in progress iteration of the previous value
                                entry = new ArrayList<RuleScript>(entry);
                                entry.remove(previous);
                            }
                            index.put(oldKey, entry);
                        }
                        // now index the new one
                        entry = index.get(key);
                        // always create a new list so that we don't affect any in progress iteration of the previous value
                        if (entry == null) {
                            entry = new ArrayList<RuleScript>();
                        } else {
                            entry = new ArrayList<RuleScript>(entry);
                        }
                        entry.add(script);
                        index.put(key, entry);
                    }
                }
            }
        }
    }

    /**
     * a 1-1 mapping from target class names which appear in rules to a script object holding the
     * rule details
     */

    private final Map<String, List<RuleScript>> targetClassIndex;

    /**
     * a 1-m mapping from target interface names which appear in rules to a script object holding the
     * rule details
     */

    private final Map<String, List<RuleScript>> targetInterfaceIndex;

    /**
     * a 1-m mapping from rule names which appear in rules to a script object holding the
     * rule details
     */

    private final Map<String, RuleScript> ruleNameIndex;

    /**
     * see if we need to do any transformation of interfaces
     * @return
     */
    public boolean checkInterfaces()
    {
        // n.b. this probably ought to be called synchronized rather than ddo its own synchronization
        // so the caller can avoid the timing window between checking and responding to the check.
        // but this is a grey area given that there is no way of knowing exactly when a transform
        // request will be sent to the Transformer and the repsonse is not to ransform anything. if
        // an uupdate affects a loaded class then it will get retransformed anyway so the risk here
        // si that the rule gets applied alittle late. we still synchornize here anyway to ensure
        // the isEmpty check does not get a partial view of the index.

        synchronized (targetInterfaceIndex) {
            return !targetInterfaceIndex.isEmpty();
        }
    }
}
