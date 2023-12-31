/**
 * Agent.java
 * Written by Gil Tene of Azul Systems, and shared under "BSD 2-Clause License"
 * as detailed below.
 *
 * @author Gil Tene
 * @version 1.0.1
 *
 * Copyright (c) 2023 Gil Tene, All rights reserved.
 * <p>
 *         Redistribution and use in source and binary forms, with or without
 *         modification, are permitted provided that the following conditions are met:
 * <p>
 *         1. Redistributions of source code must retain the above copyright notice,
 *         this list of conditions and the following disclaimer.
 * <p>
 *         2. Redistributions in binary form must reproduce the above copyright notice,
 *         this list of conditions and the following disclaimer in the documentation
 *         and/or other materials provided with the distribution.
 * <p>
 *         THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 *         AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 *         IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 *         ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 *         LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 *         CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 *         SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 *         INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 *         CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 *         ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF
 *         THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.giltene.scheduledclassloading;

import java.io.File;
import java.io.FileNotFoundException;
import java.lang.instrument.Instrumentation;
import java.time.Duration;
import java.util.*;

/**
 * ScheduledClassLoadingAgent: A java agent that loads and initializes classes according to a schedule described
 * in an input file.
 * <p>
 * Expected usage:
 *  <pre>
 *  <code>
 *      java ... -javaagent=scheduledclassloadingagent.jar={@literal <inputFile>} ...
 *  </code>
 *  </pre>
 * The input file provided as an argument to this java agent is expected to follow the following format:
 * <ul>
 *     <li>Empty lines will be ignored.</li>
 *
 *     <li>"#" will be generally ignored as a comment,, EXCEPT when "#delay={@literal <delayInMillis>}" is found.</li>
 *
 *     <li>When a #delay line is encountered, the expectation is  that the agent will wait for the specified
 *     number of milliseconds, before acting on the rest of the input.</li>
 *
 *     <li>Each non-empty, non-# line will include two strings: the first (className) being the fully qualified class
 *     name of the class to be loaded, and the second (classLoaderClassName) being the fully qualified name of the
 *     class of the classLoader to use in loading the class.</li>
 * </ul>
 * The agent will follow the schedule described in the input file, loading (and initializing at load time) each
 * specified class in the order and along the timeline described.
 * <p>
 */

public class ScheduledClassLoadingAgent {
    static final boolean verbose =
            Boolean.getBoolean("org.giltene.scheduledclassloadingagent.verbose");
    static final String reportClassNamesThatInclude =
            System.getProperty("org.giltene.scheduledclassloadingagent.reportClassNamesThatInclude");

    static final Map<ClassLoader, ClassLoader> allClassLoaders = new WeakHashMap<>();
    static final Map<String, ClassLoader> uniqueClassLoadersByLoaderClassName = new WeakHashMap<>();
    static final Map<String, ClassLoader> uniqueClassLoadersByLoaderCanonicalClassName = new WeakHashMap<>();
    static final ClassLoader NONUNIQUE_CLASSLOADER_SENTINEL = new SentinelClassLoader();
    static final ClassLoader DEFAULT_LOADER_SENTINEL = ScheduledClassLoadingAgent.class.getClassLoader();

    static class SentinelClassLoader extends ClassLoader {
        SentinelClassLoader() {
        }
    }
    static void scanForClassLoaders(Instrumentation instrumentation) {
        synchronized (allClassLoaders) {
            for (Class<?> c : instrumentation.getAllLoadedClasses()) {
                ClassLoader loader = c.getClassLoader();
                if ((loader != null) && !allClassLoaders.containsKey(loader)) {
                    allClassLoaders.put(loader, loader.getParent());
                    Class<?> loaderClass = loader.getClass();
                    String loaderClassName = loaderClass.getName();
                    String loaderCanonicalClassName = loaderClass.getCanonicalName();
                    if (!uniqueClassLoadersByLoaderClassName.containsKey(loaderClassName) &&
                            !uniqueClassLoadersByLoaderCanonicalClassName.containsKey(loaderCanonicalClassName)) {
                        uniqueClassLoadersByLoaderClassName.put(loaderClassName, loader);
                        uniqueClassLoadersByLoaderCanonicalClassName.put(loaderCanonicalClassName, loader);
                    } else {
                        uniqueClassLoadersByLoaderClassName.put(loaderClassName, NONUNIQUE_CLASSLOADER_SENTINEL);
                        uniqueClassLoadersByLoaderCanonicalClassName.put(loaderCanonicalClassName, NONUNIQUE_CLASSLOADER_SENTINEL);
                    }
                }
            }
        }
    }

    static void reportClassLoaders() {
        LOG("Class Loaders found:");
        for (ClassLoader loader : allClassLoaders.keySet()) {
            LOG("\t Loader Class:" + loader.getClass() + ", Loader Parent: " + loader.getParent());
        }
        LOG("\t----- Of which the following are unique: ------");
        for (String loaderClassName : uniqueClassLoadersByLoaderClassName.keySet()) {
            ClassLoader loader = uniqueClassLoadersByLoaderClassName.get(loaderClassName);
            if (loader != null) {
                LOG("\t Loader Class:" + loaderClassName + ", Loader Parent: " + loader.getParent());
            }
        }
    }

    static void reportInterestingClasses(Instrumentation instrumentation) {
        if ((!verbose) || (reportClassNamesThatInclude == null)) {
            return;
        }
        LOG("interesting classes with \"" + reportClassNamesThatInclude + "\" in their names:");
        for (Class<?> c : instrumentation.getAllLoadedClasses()) {
            String name = c.getCanonicalName();
            if (name != null && name.contains(reportClassNamesThatInclude)) {
                LOG("\t Class:" + c.getName() + ", " + c.getCanonicalName() +
                        ", Loader:" + c.getClassLoader());
            }
        }
    }

    static class VerboseReportingThread extends Thread {
        static final int verboseReportingPeriodMillis =
                Integer.getInteger("org.giltene.scheduledclassloadingagent.verboseReportingPeriod", 1000);

        final Instrumentation instrumentation;
        final String args;

        VerboseReportingThread(String args, Instrumentation instrumentation) {
            this.args = args;
            this.instrumentation = instrumentation;
        }

        @Override
        public void run() {
            while (true) {
                scanForClassLoaders(instrumentation);
                reportClassLoaders();
                reportInterestingClasses(instrumentation);
                try {
                    Thread.sleep(verboseReportingPeriodMillis);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    static class AgentLoaderThread extends Thread {
        final List<Object> workList = new ArrayList<>();
        final Instrumentation instrumentation;

        AgentLoaderThread(String args, Instrumentation instrumentation) {
            this.instrumentation = instrumentation;
            if (args != null) {
                final String inputFileName = args;
                parseInputFile(inputFileName);
            }
        }

        /**
         * Expected input file format:
         * - "#" will be generally ignored as a comment,, EXCEPT when "#delay=delayInMillis" is found OR
         *   a "#continueAt=millis is found.
         * - Empty lines will be ignored.
         * - When a #delay line is encountered, the expectation is that that the agent will wait for that
         *   many millis, at that point, before acting on the rest of the input.
         * - Each non-# lines will include two strings: the first being the fully qualified class name of the class to
         *   be loaded, and the second being the fully qualified name of the class of the classLoader to use in
         *   loading the class.
         */
        void parseInputFile(String inputFileName) {
            try {
                final Scanner scanner = new Scanner(new File(inputFileName));
                while (scanner.hasNext()) {
                    String line = scanner.nextLine();
                    if (line.isEmpty()) {
                        // skip empty line
                    } else if (line.startsWith("#delay=")) {
                        String delayInMillisString = line.substring("#delay=".length());
                        try {
                            int delayInMillis = Integer.parseInt(delayInMillisString);
                            workList.add(Duration.ofMillis(delayInMillis));
                        } catch (NumberFormatException ex) {
                            LOG_ERROR("ScheduledClassLoadingAgent: failed to parse invalid delay directive format: " +
                                            line);
                        }
                    } else if (line.startsWith("#")) {
                        // skip comment line
                    } else {
                        String[] words = line.split("\\s+");
                        if (words.length >= 2) {
                            String className = words[0];
                            String classLoaderClassName = words[1];
                            workList.add(new ClassToLoadEntry(className, classLoaderClassName));
                        } else {
                            LOG_ERROR(
                                    "ScheduledClassLoadingAgent: failed to parse invalid \"className classLoaderClassName\" line: " +
                                            line);
                        }
                    }
                }
            } catch (FileNotFoundException ex) {
                LOG_ERROR("ScheduledClassLoadingAgent: could not open input file " + inputFileName);
            }
        }

        @Override
        public void run() {
            try {
                scanForClassLoaders(instrumentation);
                for (Object entry : workList) {
                    if (entry instanceof Duration) {
                        Duration duration = (Duration) entry;
                        LOG("ScheduledClassLoadingAgent: sleeping for " + duration.toMillis() +" msec");
                        Thread.sleep(duration.toMillis());
                        scanForClassLoaders(instrumentation);
                    } else if (entry instanceof ClassToLoadEntry) {
                        ClassToLoadEntry ctlEntry = (ClassToLoadEntry) entry;
                        ClassLoader loader = ctlEntry.classLoaderClassName.equalsIgnoreCase("default") ?
                                DEFAULT_LOADER_SENTINEL :
                                ((uniqueClassLoadersByLoaderClassName.get(ctlEntry.classLoaderClassName) == null) ?
                                        uniqueClassLoadersByLoaderCanonicalClassName.get(ctlEntry.getClassLoaderClassName()):
                                        uniqueClassLoadersByLoaderClassName.get(ctlEntry.getClassLoaderClassName())
                                );
                        if ((loader != null) && (loader != NONUNIQUE_CLASSLOADER_SENTINEL))  {
                            try {
                                if (loader == DEFAULT_LOADER_SENTINEL) {
                                    Class.forName(ctlEntry.getClassName());
                                } else {
                                    Class.forName(ctlEntry.getClassName(), true, loader);
                                }
                                LOG("ScheduledClassLoadingAgent: loaded class " +
                                        ctlEntry.className + " using " + ctlEntry.classLoaderClassName);
                            } catch (ClassNotFoundException ex) {
                                LOG_ERROR("ScheduledClassLoadingAgent: failed to load class " +
                                        ctlEntry.className + " using " + ctlEntry.classLoaderClassName);
                            }
                        } else {
                            LOG_ERROR("ScheduledClassLoadingAgent: could not locate unique loader class " +
                                    ctlEntry.classLoaderClassName);
                            LOG("=======================");
                            reportClassLoaders();
                            LOG("=======================");
                        }
                    } else {
                        throw new RuntimeException("Unexpected workList entry type; Terminating workList processing.");
                    }
                }
            } catch (InterruptedException ex) {
                // do nothing on interruption,
            }
        }

        static class ClassToLoadEntry {
            private final String className;
            private final String classLoaderClassName;

            ClassToLoadEntry(String className, String classLoaderClassName) {
                this.className = className;
                this.classLoaderClassName = classLoaderClassName;
            }

            public String getClassName() {
                return className;
            }

            public String getClassLoaderClassName() {
                return classLoaderClassName;
            }
        }
    }

    static void LOG(String line) {
        if (verbose) {
            System.out.println(line);
        }
    }

    static void LOG_ERROR(String line) {
        System.out.println(line);
    }

    /**
     * Agent premain entry point.
     * @param args Agent options string
     * @param instrumentation instrumentation provided to the agent
     */
    public static void premain(String args, Instrumentation instrumentation) {
        LOG("ScheduledClassLoadingAgent: premain(): args = " + args);
        if (verbose) {
            VerboseReportingThread verboseReportingThread = new VerboseReportingThread(args, instrumentation);
            verboseReportingThread.setDaemon(true);
            verboseReportingThread.start();
        }
        AgentLoaderThread agentloaderThread = new AgentLoaderThread(args, instrumentation);
        agentloaderThread.setDaemon(true);
        agentloaderThread.start();
        try {
            Thread.sleep(2000);
        } catch (InterruptedException ignored) {
        }
    }
}