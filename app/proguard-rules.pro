-keep class kotlinx.serialization.** { *; }
-keep class com.agentdroid.** { *; }

# JGit can optionally publish JVM JMX metrics and bind an SLF4J implementation. Android has
# neither JMX nor a required SLF4J binder, and AgentDroid does not enable JGit monitoring.
-dontwarn java.lang.management.ManagementFactory
-dontwarn javax.management.**
-dontwarn org.slf4j.impl.StaticLoggerBinder
