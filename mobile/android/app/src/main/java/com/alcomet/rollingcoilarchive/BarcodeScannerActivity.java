package com.alcomet.rollingcoilarchive;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.Camera;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.util.*;

@SuppressWarnings("deprecation")
public class BarcodeScannerActivity extends Activity implements SurfaceHolder.Callback,Camera.PreviewCallback {
    public static final String EXTRA_RESULT="scan_result";
    private static final int REQ_CAMERA=77;
    private SurfaceView surface;
    private Camera camera;
    private boolean decoding;
    private boolean torchOn;
    private TextView status;
    private Button torch;
    private final Handler handler=new Handler(Looper.getMainLooper());

    private final List<BarcodeFormat> formats=Arrays.asList(
        BarcodeFormat.QR_CODE,
        BarcodeFormat.CODE_128,
        BarcodeFormat.CODE_39,
        BarcodeFormat.CODE_93,
        BarcodeFormat.ITF,
        BarcodeFormat.DATA_MATRIX,
        BarcodeFormat.EAN_13,
        BarcodeFormat.EAN_8,
        BarcodeFormat.UPC_A
    );

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        build();
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.CAMERA},REQ_CAMERA);
        }
    }

    private void build(){
        FrameLayout root=new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface=new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface,new FrameLayout.LayoutParams(-1,-1));

        View guide=new View(this){
            final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c){
                super.onDraw(c);
                float m=dp(28),h=dp(150),top=(getHeight()-h)/2f;
                RectF r=new RectF(m,top,getWidth()-m,top+h);
                p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2));p.setColor(0xff58c7ff);
                c.drawRoundRect(r,dp(12),dp(12),p);
            }
        };
        root.addView(guide,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout top=new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setGravity(Gravity.CENTER);
        top.setPadding(dp(12),dp(10),dp(12),dp(10));
        top.setBackgroundColor(0x99000000);
        TextView title=new TextView(this);
        title.setText("SCAN COIL QR / BARCODE");title.setTextColor(Color.WHITE);title.setTextSize(15);title.setGravity(Gravity.CENTER);
        top.addView(title,new LinearLayout.LayoutParams(-1,-2));
        status=new TextView(this);
        status.setText("For 1D barcode, keep the whole barcode horizontal inside the blue frame");status.setTextColor(0xffd5e9f5);status.setTextSize(12);status.setGravity(Gravity.CENTER);
        top.addView(status,new LinearLayout.LayoutParams(-1,-2));
        FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(-1,dp(82),Gravity.TOP);
        root.addView(top,tp);

        LinearLayout bottom=new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER);bottom.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel=new Button(this);cancel.setText("Cancel");cancel.setOnClickListener(v->finish());
        torch=new Button(this);torch.setText("Torch");torch.setOnClickListener(v->toggleTorch());
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(dp(120),dp(52));bp.setMargins(dp(6),0,dp(6),0);
        bottom.addView(cancel,bp);bottom.addView(torch,bp);
        FrameLayout.LayoutParams btm=new FrameLayout.LayoutParams(-1,dp(76),Gravity.BOTTOM);btm.setMargins(0,0,0,dp(10));
        root.addView(bottom,btm);
        setContentView(root);
    }

    @Override public void onRequestPermissionsResult(int r,String[]p,int[]g){
        super.onRequestPermissionsResult(r,p,g);
        if(r!=REQ_CAMERA)return;
        if(g.length==0||g[0]!=PackageManager.PERMISSION_GRANTED){
            Toast.makeText(this,"Camera permission is required for scanning.",Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if(surface!=null)startCamera(surface.getHolder());
    }

    @Override public void surfaceCreated(SurfaceHolder h){startCamera(h);}
    @Override public void surfaceChanged(SurfaceHolder h,int f,int w,int ht){scheduleFrame();}
    @Override public void surfaceDestroyed(SurfaceHolder h){stopCamera();}

    private void startCamera(SurfaceHolder h){
        if(camera!=null||checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;
        try{
            camera=Camera.open();
            camera.setPreviewDisplay(h);
            camera.setDisplayOrientation(90);
            Camera.Parameters p=camera.getParameters();
            List<String>m=p.getSupportedFocusModes();
            if(m!=null&&m.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO))p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            else if(m!=null&&m.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            else if(m!=null&&m.contains(Camera.Parameters.FOCUS_MODE_AUTO))p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            if(p.isZoomSupported()&&p.getMaxZoom()>0){
                // Small initial zoom helps narrow 1D bars occupy more pixels without forcing the user too close.
                p.setZoom(Math.min(1,p.getMaxZoom()));
            }
            camera.setParameters(p);
            camera.startPreview();
            scheduleFrame();
        }catch(Exception e){
            Toast.makeText(this,"Camera unavailable: "+e.getClass().getSimpleName(),Toast.LENGTH_LONG).show();
            stopCamera();
        }
    }

    private void toggleTorch(){
        if(camera==null)return;
        try{
            Camera.Parameters p=camera.getParameters();
            List<String> modes=p.getSupportedFlashModes();
            if(modes==null||!modes.contains(Camera.Parameters.FLASH_MODE_TORCH)){toast("Torch is not available on this camera.");return;}
            torchOn=!torchOn;p.setFlashMode(torchOn?Camera.Parameters.FLASH_MODE_TORCH:Camera.Parameters.FLASH_MODE_OFF);camera.setParameters(p);
            torch.setText(torchOn?"Torch ON":"Torch");
        }catch(Exception e){toast("Torch unavailable.");}
    }

    private void scheduleFrame(){
        if(camera==null||decoding)return;
        decoding=true;
        try{camera.setOneShotPreviewCallback(this);}catch(Exception e){decoding=false;}
    }

    @Override public void onPreviewFrame(byte[]data,Camera c){
        decoding=false;
        if(data==null||c==null)return;
        Result r=null;
        try{
            Camera.Size z=c.getParameters().getPreviewSize();
            r=decodeBest(data,z.width,z.height);
            if(r==null){
                byte[]cw=rotateCW(data,z.width,z.height);
                r=decodeBest(cw,z.height,z.width);
            }
            if(r==null){
                byte[]ccw=rotateCCW(data,z.width,z.height);
                r=decodeBest(ccw,z.height,z.width);
            }
            if(r!=null){
                if(status!=null)status.setText("Read: "+r.getText());
                Intent i=new Intent().putExtra(EXTRA_RESULT,r.getText());
                setResult(RESULT_OK,i);
                finish();
                return;
            }
        }catch(Exception ignored){}
        handler.postDelayed(this::scheduleFrame,90);
    }

    private Result decodeBest(byte[]yuv,int w,int h){
        // 1D labels are much more reliable when the reader is also given center crops,
        // not only the full camera frame with surrounding report text/buttons.
        Result r=decodeRegion(yuv,w,h,0,0,w,h);
        if(r!=null)return r;
        int ch=Math.max(1,(int)(h*0.58f));
        r=decodeRegion(yuv,w,h,0,(h-ch)/2,w,ch);
        if(r!=null)return r;
        int cw=Math.max(1,(int)(w*0.94f));
        int ch2=Math.max(1,(int)(h*0.38f));
        return decodeRegion(yuv,w,h,(w-cw)/2,(h-ch2)/2,cw,ch2);
    }

    private Result decodeRegion(byte[]yuv,int w,int h,int left,int top,int cw,int ch){
        try{
            PlanarYUVLuminanceSource src=new PlanarYUVLuminanceSource(yuv,w,h,left,top,cw,ch,false);
            Map<DecodeHintType,Object>hints=new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.POSSIBLE_FORMATS,formats);
            hints.put(DecodeHintType.TRY_HARDER,Boolean.TRUE);
            hints.put(DecodeHintType.CHARACTER_SET,"UTF-8");
            MultiFormatReader reader=new MultiFormatReader();
            BinaryBitmap bmp=new BinaryBitmap(new HybridBinarizer(src));
            try{return reader.decode(bmp,hints);}catch(NotFoundException ignored){}
            // Handles displays/labels with reversed contrast or camera exposure edge cases.
            BinaryBitmap inv=new BinaryBitmap(new HybridBinarizer(src.invert()));
            return reader.decode(inv,hints);
        }catch(Exception e){return null;}
    }

    private byte[] rotateCW(byte[]d,int w,int h){
        byte[]r=new byte[w*h];int k=0;
        for(int x=0;x<w;x++)for(int y=h-1;y>=0;y--)r[k++]=d[y*w+x];
        return r;
    }
    private byte[] rotateCCW(byte[]d,int w,int h){
        byte[]r=new byte[w*h];int k=0;
        for(int x=w-1;x>=0;x--)for(int y=0;y<h;y++)r[k++]=d[y*w+x];
        return r;
    }
    private void stopCamera(){
        handler.removeCallbacksAndMessages(null);
        if(camera!=null){
            try{Camera.Parameters p=camera.getParameters();if(torchOn){p.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);camera.setParameters(p);}camera.setPreviewCallback(null);camera.stopPreview();camera.release();}catch(Exception ignored){}
            camera=null;
        }
        torchOn=false;decoding=false;
    }
    @Override protected void onPause(){stopCamera();super.onPause();}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
