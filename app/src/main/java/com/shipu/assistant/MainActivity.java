package com.shipu.assistant;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.webkit.*;
import android.widget.FrameLayout;
import com.shipu.assistant.music.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int PICK_SCORE=100;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor(),previewWorker=Executors.newSingleThreadExecutor();
    private LibraryRepository repository;
    private WebView web;
    private PianoAudio piano;
    private Score score;
    private PracticeEngine engine;
    private String currentId="demo",lastCompletedRun="";
    private boolean destroyed,sessionSaved,bridgeReady;
    private long startedAt;
    private volatile long previewGeneration;
    private PlaybackTimeline playback;
    private String playbackState="stopped";
    private double playbackBpm;
    private long playbackOffset,playbackOrigin,lastPlaybackReport;
    private int nextPlaybackNote,lastPlaybackPosition=-1;
    private final Map<Integer,Integer> playingStreams=new HashMap<Integer,Integer>();
    private final BroadcastReceiver jobReceiver=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){try{publishJob();}catch(Exception failure){error(failure);}}};

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        try{repository=LibraryRepository.get(this);}catch(Exception failure){new AlertDialog.Builder(this).setMessage("本机乐谱库无法读取："+failure.getMessage()).setPositiveButton("关闭",(d,w)->finish()).show();return;}
        getSharedPreferences("preferences",MODE_PRIVATE).edit().clear().apply();
        score=Score.demo();engine=new PracticeEngine(score);piano=new PianoAudio(this);setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.rgb(246,245,240));web=new WebView(this);
        if((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)WebView.setWebContentsDebuggingEnabled(true);
        root.addView(web,new FrameLayout.LayoutParams(-1,-1));setContentView(root);
        root.setOnApplyWindowInsetsListener((view,insets)->{view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets.consumeSystemWindowInsets();});
        web.setBackgroundColor(Color.rgb(246,245,240));web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);web.addJavascriptInterface(new Bridge(),"Android");
        final LocalAssetHost assets=new LocalAssetHost(this,null);
        web.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}@Override public boolean shouldOverrideUrlLoading(WebView view,String url){return true;}@Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return assets.response(request.getUrl(),request.getMethod());}});
        IntentFilter filter=new IntentFilter(RecognitionService.UPDATE);if(Build.VERSION.SDK_INT>=33)registerReceiver(jobReceiver,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(jobReceiver,filter);
        if(saved!=null)currentId=saved.getString("currentId","demo");String target=getIntent().getStringExtra("scoreId");if(target!=null)currentId=target;
        try{loadScore(currentId);}catch(Exception ignored){currentId="demo";score=Score.demo();engine=new PracticeEngine(score);}
        web.loadUrl(LocalAssetHost.ORIGIN+"/index.html");
    }
    private final class Bridge{
        @JavascriptInterface public void postMessage(String text){if(text==null||text.length()>16*1024*1024){error(new Exception("操作数据过大，请分段导入"));return;}try{JSONObject data=new JSONObject(text);handler.post(()->{try{action(data);}catch(Exception failure){error(failure);}});}catch(Exception failure){error(failure);}}
        @JavascriptInterface public String localOmrDiagnostics(){return RecognitionService.diagnostic(MainActivity.this);}
        @JavascriptInterface public void keyDown(int midi){if(midi<21||midi>108)return;handler.post(()->{try{stopPlayback();piano.play(midi);if(engine==null)return;PracticeEngine.Result result=engine.press(midi);sendState(result.name(),midi);if(engine.isFinished())saveSession();}catch(Exception failure){error(failure);}});}
        @JavascriptInterface public void audition(int midi){if(midi>=21&&midi<=108)handler.post(()->piano.play(midi));}
    }
    private void action(JSONObject a)throws Exception{
        switch(a.optString("action")){
            case "boot":bridgeReady=true;sendBoot();publishJob();if(!"demo".equals(currentId))send("opened",new JSONObject().put("ready",score!=null));break;
            case "import":Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");picker.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/*","application/pdf","application/xml","text/xml","application/vnd.recordare.musicxml","application/vnd.recordare.musicxml+xml","application/zip","application/octet-stream"});startActivityForResult(picker,PICK_SCORE);break;
            case "open":open(a.getString("id"));break;
            case "preview":preview(a.optString("id",currentId),a.optInt("page",0),a.optLong("requestId",0));break;
            case "configure":requireScore();stopPlayback();engine.configure(PracticeEngine.Hand.valueOf(a.optString("hand","RIGHT")),PracticeEngine.Mode.valueOf(a.optString("mode","NOTE")),a.optInt("start",0),a.optInt("end",score.measures.size()-1),a.optBoolean("loop",false),a.optBoolean("swap",false));sessionSaved=false;startedAt=System.currentTimeMillis();sendState("START",-1);break;
            case "restart":requireScore();stopPlayback();engine.restart();sessionSaved=false;startedAt=System.currentTimeMillis();sendState("START",-1);break;
            case "previous":requireScore();stopPlayback();engine.previous();sendState("PREVIOUS",-1);break;
            case "finishSession":stopPlayback();saveSession();sendBoot();break;
            case "playback":playback(a);break;
            case "localRecognize":confirmRecognition(a.optString("id",currentId));break;
            case "localRecognitionCancel":case "cancelLocalRecognition":cancelRecognition(a.optString("id",currentId),a.optString("runId",""));break;
            case "copyLocalDiagnostics":String text=a.optString("text",RecognitionService.diagnostic(this));if(text.length()>64*1024)throw new Exception("运行信息过长");new JSONObject(text);((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("识谱助手运行信息",text));message("运行信息已复制，可以粘贴发送");break;
            case "saveScore":stopPlayback();Score edited=ScoreJson.decode(a.getJSONObject("score"));if("demo".equals(currentId)){currentId=UUID.randomUUID().toString();repository.add(new JSONObject().put("id",currentId).put("title",edited.title).put("mime","application/musicxml").put("created",System.currentTimeMillis()));}JSONObject job=RecognitionService.snapshot(this);if(currentId.equals(job.optString("id"))&&"running".equals(job.optString("state")))throw new Exception("请等待这份乐谱识别完成后再保存校对");repository.updateScore(currentId,ScoreJson.encode(edited),true,0);score=edited;engine=new PracticeEngine(score);sendBoot();message("校对已保存，可以开始练习");break;
            case "deleteScore":deleteScore(a.optString("id",currentId));break;
            case "delete":deleteScore(currentId);break;
            case "cancelRecognition":send("busy",new JSONObject().put("active",false));break;
            case "finishApp":finish();break;
        }
    }
    private void requireScore()throws Exception{if(score==null||engine==null)throw new Exception("请先识别并校对乐谱");}
    private JSONObject find(String id)throws Exception{if("demo".equals(id))return new JSONObject().put("id","demo").put("title",Score.demo().title).put("reviewed",true);return repository.find(id);}
    private void loadScore(String id)throws Exception{JSONObject item=find(id);score="demo".equals(id)?Score.demo():item.has("score")?ScoreJson.decode(item.getJSONObject("score")):null;engine=score==null?null:new PracticeEngine(score);sessionSaved=false;startedAt=System.currentTimeMillis();}
    private void open(String id)throws Exception{stopPlayback();find(id);currentId=id;previewGeneration++;loadScore(id);sendBoot();send("opened",new JSONObject().put("ready",score!=null));}
    private String webViewVersion(){try{if(Build.VERSION.SDK_INT>=26&&WebView.getCurrentWebViewPackage()!=null)return WebView.getCurrentWebViewPackage().versionName;}catch(Exception ignored){}return "未知";}
    private void sendBoot()throws Exception{JSONObject data=new JSONObject().put("library",repository.library()).put("history",repository.history()).put("currentId",currentId).put("current",find(currentId)).put("webViewVersion",webViewVersion()).put("localRecognition",true).put("backgroundRecognition",true).put("appVersion","0.3.0").put("job",RecognitionService.snapshot(this)).put("score",score==null?JSONObject.NULL:ScoreJson.encode(score));send("boot",data);if(engine!=null)sendState("READY",-1);}
    private void publishJob()throws Exception{JSONObject job=RecognitionService.snapshot(this);if("complete".equals(job.optString("state"))&&!job.optString("runId").equals(lastCompletedRun)){lastCompletedRun=job.optString("runId");if(currentId.equals(job.optString("id"))){stopPlayback();loadScore(currentId);}sendBoot();}send("recognitionStatus",job);}
    private void sendState(String result,int played)throws Exception{
        if(engine==null)return;JSONObject data=new JSONObject().put("result",result).put("played",played).put("index",engine.currentIndex()).put("total",engine.size()).put("finished",engine.isFinished()).put("correct",engine.correctNotes).put("wrong",engine.wrongNotes).put("accuracy",engine.accuracy()).put("loops",engine.loops);JSONArray matched=new JSONArray();for(Integer midi:engine.matched())matched.put(midi);data.put("matched",matched);PracticeEngine.Step step=engine.current();
        if(step==null)data.put("step",JSONObject.NULL);else{JSONArray notes=new JSONArray();for(Score.Note n:step.notes)notes.put(new JSONObject().put("midi",n.midi).put("staff",n.staff).put("name",n.name));data.put("step",new JSONObject().put("measureIndex",step.measureIndex).put("measureNumber",step.measureNumber).put("eventIndex",step.eventIndex).put("noteIndex",step.noteIndex).put("onset",step.onset).put("notes",notes));}send("state",data);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=PICK_SCORE||result!=RESULT_OK||data==null||data.getData()==null)return;final Uri uri=data.getData();busy("正在导入乐谱…");worker.submit(()->{File output=null;try{
        String name="我的乐谱";try(Cursor cursor=getContentResolver().query(uri,null,null,null,null)){if(cursor!=null&&cursor.moveToFirst()){int i=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(i>=0)name=cursor.getString(i);}}
        String mime=getContentResolver().getType(uri);if(mime==null)mime="application/octet-stream";String lower=name.toLowerCase(Locale.ROOT);boolean xml=lower.endsWith(".xml")||lower.endsWith(".musicxml")||lower.endsWith(".mxl"),pdf=lower.endsWith(".pdf")||mime.equals("application/pdf");if(!xml&&!pdf&&!mime.startsWith("image/"))throw new Exception("请选择图片、PDF、MusicXML或MXL文件");
        String id=UUID.randomUUID().toString();output=repository.newSource(id);try(InputStream input=getContentResolver().openInputStream(uri);OutputStream destination=new FileOutputStream(output)){if(input==null)throw new IOException("文件无法读取");byte[] buffer=new byte[16384];int n,total=0;while((n=input.read(buffer))!=-1){if((total+=n)>20*1024*1024)throw new Exception("文件超过20MB，请压缩或分段导入");destination.write(buffer,0,n);}}
        JSONObject item=new JSONObject().put("id",id).put("title",name.replaceFirst("\\.[^.]+$","")).put("filename",name).put("mime",pdf?"application/pdf":mime).put("file",id).put("created",System.currentTimeMillis()).put("reviewed",false);if(xml)try(InputStream input=new FileInputStream(output)){JSONObject encoded=ScoreJson.encode(MusicXmlParser.parse(input,name));ScoreJson.decode(encoded);item.put("score",encoded);}repository.add(item);handler.post(()->{try{open(id);send("busy",new JSONObject().put("active",false));}catch(Exception failure){error(failure);}});
    }catch(Exception failure){if(output!=null)output.delete();error(failure);}});}
    private void preview(String id,int page,long requestId)throws Exception{if(!currentId.equals(id))return;JSONObject item=find(id);if(!item.has("file"))return;String mime=item.optString("mime");if(!mime.equals("application/pdf")&&!mime.startsWith("image/"))return;File file=repository.source(item);long generation=++previewGeneration;previewWorker.submit(()->{try{if(generation!=previewGeneration)return;JSONObject image=ScoreImageRenderer.render(file,mime,page,false);image.put("id",id).put("requestId",requestId).put("src","data:"+image.getString("mime")+";base64,"+image.getString("base64"));image.remove("base64");handler.post(()->{if(!destroyed&&generation==previewGeneration&&currentId.equals(id))send("preview",image);});}catch(Exception failure){if(generation==previewGeneration)error(failure);}catch(OutOfMemoryError memory){error(new Exception("预览页面占用内存过多，请缩小后导入"));}});}
    private void confirmRecognition(String id)throws Exception{JSONObject item=find(id);String mime=item.optString("mime");if(!item.has("file")||(!mime.equals("application/pdf")&&!mime.startsWith("image/")))throw new Exception("请先导入图片或PDF，MusicXML可以直接练习");JSONObject existing=RecognitionService.snapshot(this);if("running".equals(existing.optString("state"))){message("已有乐谱正在识别，请等待完成或先取消当前任务");return;}
        new AlertDialog.Builder(this).setTitle("在手机上识别这份乐谱？").setMessage(item.optString("filename",item.optString("title"))+"\n\n乐谱和模型均在本机处理，不会上传。每页将按顺序识别，完成后仍需校对。\n\n可以锁屏或切换到其他应用，通知栏会显示进度。系统强制停止应用会中断任务，再次开始可接着已完成的页面处理。").setNegativeButton("取消",null).setPositiveButton("开始识谱",(dialog,which)->{if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},201);Intent service=new Intent(this,RecognitionService.class).putExtra("id",id);if(Build.VERSION.SDK_INT>=26)startForegroundService(service);else startService(service);}).show();
    }
    private void cancelRecognition(String id,String token){RecognitionService.cancel(this,id,token);}
    private void deleteScore(String id)throws Exception{if("demo".equals(id)){message("示例曲用于体验，可以删除自己导入的乐谱");return;}JSONObject item=find(id);new AlertDialog.Builder(this).setTitle("删除“"+item.optString("title")+"”？").setMessage("这份乐谱的原文件、识别结果和校对内容将从本机删除。").setNegativeButton("保留",null).setPositiveButton("删除",(dialog,which)->{try{JSONObject job=RecognitionService.snapshot(this);if(id.equals(job.optString("id"))&&"running".equals(job.optString("state")))cancelRecognition(id,job.optString("runId"));repository.remove(id);if(id.equals(currentId))open("demo");else sendBoot();send("deleted",new JSONObject().put("id",id));}catch(Exception failure){error(failure);}}).show();}
    private void saveSession()throws Exception{if(sessionSaved||engine==null||engine.correctNotes+engine.wrongNotes==0)return;repository.addHistory(new JSONObject().put("title",score.title).put("time",System.currentTimeMillis()).put("accuracy",engine.accuracy()).put("correct",engine.correctNotes).put("wrong",engine.wrongNotes).put("seconds",Math.max(1,(System.currentTimeMillis()-startedAt)/1000)));sessionSaved=true;}
    private void playback(JSONObject request)throws Exception{requireScore();String command=request.optString("command");if("pause".equals(command)){pausePlayback();return;}if("stop".equals(command)){stopPlayback();return;}if(!"play".equals(command)&&!"tempo".equals(command))return;double bpm=request.optDouble("bpm",0);if(!Double.isFinite(bpm)||bpm<0||bpm>300)throw new Exception("播放速度无效");
        if("tempo".equals(command)){
            if(playback==null||(!"playing".equals(playbackState)&&!"paused".equals(playbackState)))return;
            boolean resume="playing".equals(playbackState);long offset=resume?SystemClock.elapsedRealtime()-playbackOrigin:playbackOffset;
            double original=playbackBpm>0?playbackBpm:score.tempo;double target=bpm>0?bpm:score.tempo;
            silencePlayback();playback=PlaybackTimeline.create(score,PracticeEngine.Hand.valueOf(request.optString("hand","RIGHT")),request.optInt("start",0),request.optInt("end",score.measures.size()-1),request.optBoolean("swap",false),bpm);
            playbackBpm=bpm;playbackOffset=Math.min(playback.durationMs,Math.round(offset*original/target));nextPlaybackNote=0;lastPlaybackPosition=-1;
            while(nextPlaybackNote<playback.notes().size()&&playback.notes().get(nextPlaybackNote).startMs<playbackOffset)nextPlaybackNote++;
            while(lastPlaybackPosition+1<playback.positions().size()&&playback.positions().get(lastPlaybackPosition+1).timeMs<=playbackOffset)lastPlaybackPosition++;
            playbackState="paused";reportPlayback();if(!resume)return;
        }
        if(!"paused".equals(playbackState)){stopPlayback();playback=PlaybackTimeline.create(score,PracticeEngine.Hand.valueOf(request.optString("hand","RIGHT")),request.optInt("start",0),request.optInt("end",score.measures.size()-1),request.optBoolean("swap",false),bpm);playbackOffset=0;playbackBpm=bpm;nextPlaybackNote=0;lastPlaybackPosition=-1;}
        if(playback==null||playback.notes().isEmpty())throw new Exception("当前左右手和小节范围内没有可播放音符");playbackState="playing";playbackOrigin=SystemClock.elapsedRealtime()-playbackOffset;lastPlaybackReport=0;for(int i=0;i<nextPlaybackNote;i++){PlaybackTimeline.NoteEvent note=playback.notes().get(i);if(note.startMs<playbackOffset&&note.endMs>playbackOffset)playingStreams.put(i,piano.play(note.midi));}handler.removeCallbacks(playbackTick);handler.post(playbackTick);
    }
    private final Runnable playbackTick=new Runnable(){@Override public void run(){if(!"playing".equals(playbackState)||playback==null||destroyed)return;long now=SystemClock.elapsedRealtime()-playbackOrigin;playbackOffset=now;Iterator<Map.Entry<Integer,Integer>> sounding=playingStreams.entrySet().iterator();while(sounding.hasNext()){Map.Entry<Integer,Integer> entry=sounding.next();if(playback.notes().get(entry.getKey()).endMs<=now){piano.stop(entry.getValue());sounding.remove();}}while(nextPlaybackNote<playback.notes().size()&&playback.notes().get(nextPlaybackNote).startMs<=now){PlaybackTimeline.NoteEvent note=playback.notes().get(nextPlaybackNote);if(note.endMs>now)playingStreams.put(nextPlaybackNote,piano.play(note.midi));nextPlaybackNote++;}int position=lastPlaybackPosition;while(position+1<playback.positions().size()&&playback.positions().get(position+1).timeMs<=now)position++;if(position!=lastPlaybackPosition||now-lastPlaybackReport>=250){lastPlaybackPosition=position;lastPlaybackReport=now;reportPlayback();}if(now>=playback.durationMs){playbackState="complete";silencePlayback();reportPlayback();return;}handler.postDelayed(this,15);}};
    private void silencePlayback(){for(Integer stream:playingStreams.values())piano.stop(stream);playingStreams.clear();handler.removeCallbacks(playbackTick);}
    private void pausePlayback(){if(!"playing".equals(playbackState))return;playbackOffset=SystemClock.elapsedRealtime()-playbackOrigin;playbackState="paused";silencePlayback();reportPlayback();}
    private void stopPlayback(){if("stopped".equals(playbackState))return;playbackState="stopped";playbackOffset=0;lastPlaybackPosition=-1;silencePlayback();reportPlayback();playback=null;}
    private void reportPlayback(){try{JSONObject data=new JSONObject().put("state",playbackState).put("bpm",playbackBpm).put("positionMs",playbackOffset).put("durationMs",playback==null?0:playback.durationMs);if(playback!=null&&lastPlaybackPosition>=0){PlaybackTimeline.Position p=playback.positions().get(lastPlaybackPosition);data.put("measureIndex",p.measureIndex).put("eventIndex",p.eventIndex);}send("playbackState",data);}catch(Exception ignored){}}
    private void send(String type,JSONObject data){handler.post(()->{if(!destroyed&&web!=null)try{JSONObject envelope=new JSONObject().put("type",type).put("data",data);web.evaluateJavascript("window.receive&&window.receive("+envelope+")",null);}catch(Exception ignored){}});}
    private void message(String text)throws Exception{send("message",new JSONObject().put("text",text));}
    private void busy(String text){try{send("busy",new JSONObject().put("active",true).put("text",text));}catch(Exception ignored){}}
    private void error(Exception failure){handler.post(()->{try{send("busy",new JSONObject().put("active",false));send("error",new JSONObject().put("text",failure.getMessage()==null?"操作未完成，请重试":failure.getMessage()));}catch(Exception ignored){}});}
    @Override public void onBackPressed(){if(web!=null)web.evaluateJavascript("window.appBack&&window.appBack()",null);else super.onBackPressed();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);String id=intent.getStringExtra("scoreId");if(id!=null&&bridgeReady)try{open(id);}catch(Exception failure){error(failure);}}
    @Override protected void onSaveInstanceState(Bundle out){out.putString("currentId",currentId);super.onSaveInstanceState(out);}
    @Override protected void onResume(){super.onResume();if(bridgeReady)try{publishJob();}catch(Exception failure){error(failure);}}
    @Override protected void onPause(){super.onPause();pausePlayback();if(piano!=null)piano.silence();}
    @Override protected void onDestroy(){destroyed=true;try{unregisterReceiver(jobReceiver);}catch(Exception ignored){}stopPlayback();worker.shutdownNow();previewWorker.shutdownNow();handler.removeCallbacksAndMessages(null);if(piano!=null)piano.close();if(web!=null){web.removeJavascriptInterface("Android");web.destroy();}super.onDestroy();}
}
