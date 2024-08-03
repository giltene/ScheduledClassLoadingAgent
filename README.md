ScheduledClassLoadingAgent
----------------------------------------------
ScheduledClassLoadingAgent: A java agent that loads and initializes classes according to a schedule described in an input file.

Expected usage:
```
  java ... -javaagent:scheduledclassloadingagent.jar=<inputFile> ...
```
The input file provided as an argument to this java agent is expected to follow the following format:

 - Empty lines will be ignored.

 - `#` will be generally ignored as a comment,, EXCEPT when `"#delay=<delayInMillis>` is found.

 - When a `#delay` line is encountered, the expectation is  that the agent will wait for the specified
   number of milliseconds, before acting on the rest of the input.
    
 - Each non-empty, non-# line will include exactly two strings: the first (className) being the fully qualified 
   class name of the class to be loaded, and the second (classLoaderClassName) being the fully qualified name of the
   class of the classLoader to use in loading the class.

 - The word `default` may be used as the classLoaderClassName, for example when the intent is to load JDK
   classes.  

The agent will follow the schedule described in the input file, loading (and initializing at load time) each
specified class in the order and along the timeline described.
<p>
For example, the following example input would attempt to cause three classes in a Springboot application to
load according to the schedule described:

```
#
#delay=3000
#
com.example.springbootclassesexample.ExampleClassA org.springframework.boot.loader.LaunchedURLClassLoader
com.example.springbootclassesexample.ExampleClassB org.springframework.boot.loader.LaunchedURLClassLoader
#
#delay=2000
#
java.util.Random default
java.util.concurrent.ConcurrentSkipListMap default
com.example.springbootclassesexample.ExampleClassC org.springframework.boot.loader.LaunchedURLClassLoader
```

Some useful diagnostics properties:

 - The agent can be set to a verbose output mode (including periodic reporting on observed class loaders) by setting the property: <br>
`-Dorg.giltene.scheduledclassloadingagent.verbose=true`

 - The frequency with which the agent reports on observed loaders can be set with the property:<br>
`-Dorg.giltene.scheduledclassloadingagent.verboseReportingPeriod=<periodInMillis>`

 - To periodically report on observed classes that include a given string, set the property:<br>
`-Dorg.giltene.scheduledclassloadingagent.reportClassNamesThatInclude=<string>`

----------------------------------------------------------------------------
License: BSD 2-Clause, as described in LICENSE file
