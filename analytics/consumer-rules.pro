# SDK 无反射调用，正常情况下无需额外 keep；保险起见保留对外门面。
-keep class com.analytics.sdk.Analytics { *; }
