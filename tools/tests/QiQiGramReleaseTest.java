import org.telegram.messenger.QiQiGramRelease;
import org.json.*;
import java.nio.file.*;
public class QiQiGramReleaseTest {
 static void ok(boolean value) { if (!value) throw new AssertionError(); }
 static JSONObject release(String tag, boolean draft, String arch, boolean hash) {
  String v=tag.substring(1), name="qiqigram-"+v+"-beta-"+arch+".apk";
  JSONObject asset=new JSONObject().put("name",name).put("size",1024).put("state","uploaded")
   .put("browser_download_url","https://github.com/arcuataone/Telegram/releases/download/"+tag+"/"+name);
  if(hash)asset.put("digest","sha256:"+new String(new char[64]).replace('\0','a'));
  return new JSONObject().put("tag_name",tag).put("draft",draft).put("prerelease",true).put("assets",new JSONArray().put(asset));
 }
 public static void main(String[] args) throws Exception {
  ok(QiQiGramRelease.versionNumber("v0.03")==3);
  ok(QiQiGramRelease.versionNumber("0.10")>QiQiGramRelease.versionNumber("0.09"));
  ok(QiQiGramRelease.versionNumber("1.00")==100);
  for(String bad:new String[]{"v0.3","../0.03","v999999999999999999999.00","junk"})ok(QiQiGramRelease.versionNumber(bad)==-1);
  JSONArray releases=new JSONArray().put(release("v0.10",false,"arm64-v8a",true)).put(release("v0.09",false,"arm64-v8a",true))
   .put(release("v0.99",true,"arm64-v8a",true)).put(release("v0.98",false,"x86",true)).put(release("v0.97",false,"arm64-v8a",false));
  ok(QiQiGramRelease.latest(releases).number==10);
  JSONObject evil=release("v0.90",false,"arm64-v8a",true);
  evil.getJSONArray("assets").getJSONObject(0).put("browser_download_url","https://evil.invalid/app.apk");
  releases.put(evil);ok(QiQiGramRelease.latest(releases).number==10);
  ok(!QiQiGramRelease.assetUrl("http://github.com/arcuataone/Telegram/releases/download/v0.03/a.apk","v0.03"));
  ok(!QiQiGramRelease.assetUrl("https://github.com/else/Telegram/releases/download/v0.03/a.apk","v0.03"));
  QiQiGramRelease real=QiQiGramRelease.latest(new JSONArray(new String(Files.readAllBytes(Paths.get(args[0])),"UTF-8")));
  ok(real!=null);ok(real.number>=3);ok(real.size>1024);ok(!real.checksumUrl.isEmpty()||!real.digest.isEmpty());
  System.out.println("PASS: version ordering, beta releases, draft/architecture/hash filters, untrusted URLs, actual GitHub release "+real.version);
 }
}
