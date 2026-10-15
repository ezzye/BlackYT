// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;
import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
final class Diagnostics {
 private static File target;
 static synchronized void init(Context context) {
  if (BuildConfig.DEBUG) target=new File(context.getFilesDir(),"native-diagnostics.txt");
 }
 static synchronized void event(String stage,String detail) {
  if (!BuildConfig.DEBUG || target==null) return;
  try {
   String safe=detail.replaceAll("https?://[^\\s]+","[url]").replace('\n',' ').replace('\r',' ');
   if(safe.length()>700)safe=safe.substring(0,700);
   if(target.exists()&&target.length()>65536)target.delete();
   try(FileOutputStream out=new FileOutputStream(target,true)) {
    out.write((System.currentTimeMillis()+" "+stage+" "+safe+"\n").getBytes(StandardCharsets.UTF_8));
   }
  }catch(Exception ignored){}
 }
 static void failure(String stage,Throwable error) {
  StringBuilder text=new StringBuilder();int depth=0;
  for(Throwable e=error;e!=null&&depth++<6;e=e.getCause())text.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append(" | ");
  event(stage,text.toString());
 }
}
