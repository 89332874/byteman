package org.jboss.jbossts.test;

import org.jboss.jbossts.orchestration.annotation.EventHandler;
import org.jboss.jbossts.orchestration.annotation.EventHandlerClass;

/**
 * sample class to test event handling
 */
@EventHandlerClass
public class HandlerClass {
    @EventHandler(
            targetClass="com.arjuna.wst11.messaging.engines.CoordinatorEngine",
            targetMethod="commit",
            targetLine = 316,
            event = "engine:CoordinatorEngine = $0,\n" +
                    "recovered:boolean = engine.isRecovered(),\n" + 
                    "identifier:String = engine.getId()",
            condition = "(NOT recovered)\n" +
                    "AND\n" +
                    "debug(\"commit on non-recovered engine \" + identifier)",
            action = "debug(\"!!!killing JVM!!!\"), killJVM()"
    ) public static void handleCommit1()
    {
        // kills the JVM when a commit is attempted on a non-recovered engine
    }

    @EventHandler(
            targetClass="com.arjuna.wst11.messaging.engines.CoordinatorEngine",
            targetMethod="commit",
            targetLine = 316,
            event = "engine:CoordinatorEngine = $0,\n " +
                    "recovered:boolean = engine.isRecovered(),\n" +
                    "identifier:String = engine.getId()",
            condition = "recovered\n" +
                    "AND\n" +
                    "debug(\"commit on recovered engine \" + identifier)\n" +
                    "AND\n" +
                    "debug(\"counting down\")\n" +
                    "AND\n" +
                    "countDown(identifier)",
            action = "debug(\"countdown completed for \" + identifier)"
    ) public static void handleCommit2()
    {
        // decrements the counter identified by a recovered engine's identifier each time recovery is attempted
        // for that engine -- if the counter decrements to zero it will be deactivated
    }

    @EventHandler(
            targetClass="com.arjuna.wst11.messaging.engines.CoordinatorEngine",
            targetMethod="<init>(String, boolean, W3CEndpointReference, boolean, State)",
            targetLine = 96,
            event = "engine:CoordinatorEngine = $0,\n" +
                    "recovered:boolean = engine.isRecovered(),\n" +
                    "identifier:String = engine.getId()",
            condition = "recovered",
            action = "debug(\"adding countdown for \" + identifier),\n" +
                    "addCountDown(identifier, 2)"
    ) public static void handleNewEngine()
    {
        // activates a counter identified by a recovered engine's identifier when the engine is recreated
        // from the logged data
    }

    @EventHandler(
            targetClass="com.arjuna.wst11.messaging.engines.CoordinatorEngine",
            targetMethod="committed(Notification, AddressingProperties, ArjunaContext)",
            targetLine = -1,
            event = "engine:CoordinatorEngine = $0,\n" +
                    "recovered:boolean = engine.isRecovered()," +
                    "identifier:String = engine.getId()\n",
            condition = "recovered\n" +
                    "AND\n" +
                    "debug(\"committed on recovered engine \" + identifier)\n" +
                    "AND\n" +
                    "getCountDown(identifier)",
            action = "debug(\"!!!killing committed thread for \" + identifier + \"!!!\"),\n" +
                    "killThread()"
    ) public static void handleCommitted()
    {
        // kills the current thread when a committed message is received for an engine whose identifier identifies
        // an active counter
    }
}
