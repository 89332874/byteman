package org.jboss.jbossts.orchestration.agent.adapter.cfg;

import org.objectweb.asm.Label;

import java.util.List;
import java.util.LinkedList;
import java.util.Iterator;

/**
 * auxiliary used by CFG to store details of a specific try catch block
 */
public class TryCatchDetails
{
    /**
     * back link to the control flow graph
     */
    private CFG cfg;
    /**
     * the label identifying the start of the try catch block
     */
    private Label start;
    /**
     * the label identifying the end of the try catch block
     */
    private Label end;
    /**
     * the label identifying the start of the try catch block handler
     */
    private Label handler;
    /**
     * a list of monitor enter instructions which are still open on entry to this try catch block
     * and hence which may require closing in the associated handler
     */
    private List<CodeLocation> openEnters;
    /**
     * the name of the exception type handled by the handler or null if it is a catch all handler
     */
    private String type;
    /**
     * true if this is a trigger handler otherwise false
     */
    private boolean isTriggerHandler;

    /**
     * construct a try catch details instance
     * @param cfg
     * @param start
     * @param end
     * @param handler
     * @param type
     * @param isTriggerHandler
     */
    public TryCatchDetails(CFG cfg, Label start, Label end, Label handler, String type, boolean isTriggerHandler)
    {
        this.cfg = cfg;
        this.start = start;
        this.end = end;
        this.handler = handler;
        this.openEnters = new LinkedList<CodeLocation>();
        this.type = type;
        this.isTriggerHandler = isTriggerHandler;
    }

    // accessors

    public Label getStart() {
        return start;
    }

    public Label getEnd() {
        return end;
    }

    public Label getHandler() {
        return handler;
    }

    public String getType() {
        return type;
    }

    public boolean isTriggerHandler() {
        return isTriggerHandler;
    }

    /**
     * add a new monitor enter location to the list of open locations associated with this handler
     * @param openEnter
     */
    public void addOpenEnter(CodeLocation openEnter)
    {
        openEnters.add(openEnter);
    }

    /**
     * check if a monitor enter location belongs to the list of open locations associated with this handler
     * @param openEnter
     */
    public boolean containsOpenEnter(CodeLocation openEnter)
    {
        return openEnters.contains(openEnter);
    }

    /**
     * add all the open locations associated with this handler to the supplied list of open locations
     * @param openMonitorEnters
     */
    public void addOpenLocations(List<CodeLocation> openMonitorEnters) {
        Iterator<CodeLocation> iterator = openEnters.iterator();
        while (iterator.hasNext()) {
            CodeLocation location = iterator.next();
            if (!openMonitorEnters.contains(location)) {
                openMonitorEnters.add(location);
            }
        }
    }
}
