package com.example;

import java.lang.instrument.Instrumentation;

public class AgentDemo {
    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("premain");
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        System.out.println("agentmain");
    }
}
