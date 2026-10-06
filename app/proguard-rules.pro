# Compose 与 AndroidX 由各自 AAR 提供 consumer rules，这里只放 Lyra 自己的规则。

# 源码行号保留给崩溃栈：没有它，release 包上报的堆栈不可读
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 音源脚本由 QuickJS 执行，反射入口在 M1 落地后再补，此处不放空规则
