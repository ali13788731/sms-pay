# کلاس‌های Activity/Receiver از طریق AndroidManifest به‌صورت خودکار حفظ می‌شوند.
# قانون اضافه‌ای لازم نیست؛ فایل برای افزودن قوانین آینده نگه داشته شده است.

# برای خوانایی گزارش خطا در Play/Bazaar vitals
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# کتابخانه‌ی پرداخت بازار (Poolakey) و رابط AIDL سرویس بازار
-keep class ir.cafebazaar.poolakey.** { *; }
-keep class com.farsitel.bazaar.** { *; }
-dontwarn ir.cafebazaar.poolakey.**
