package com.alcomet.rollingcoilarchive;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
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
        TextView hint=new TextView(this);
        hint.setText("SCAN COIL QR / BARCODE\nQR · Code 128 · Code 39 · Data Matrix");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        hint.setBackgroundColor(0x88000000);
        FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(-1,dp(70),Gravity.TOP);
        root.addView(hint,hp);
        Button cancel=new Button(this);
        cancel.setText("Cancel");
        cancel.setOnClickListener(v->finish());
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(dp(110),dp(52),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        cp.setMargins(0,0,0,dp(28));
        root.addView(cancel,cp);
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
        // surfaceCreated may already have fired while the permission dialog was open.
        // Start explicitly after first-time permission grant so the scanner never stays black.
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
            if(m!=null&&m.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)){
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                camera.setParameters(p);
            }
            camera.startPreview();
            scheduleFrame();
        }catch(Exception e){
            Toast.makeText(this,"Camera unavailable: "+e.getClass().getSimpleName(),Toast.LENGTH_LONG).show();
            stopCamera();
        }
    }

    private void scheduleFrame(){
        if(camera==null||decoding)return;
        decoding=true;
        try{camera.setOneShotPreviewCallback(this);}catch(Exception e){decoding=false;}
    }

    @Override public void onPreviewFrame(byte[]data,Camera c){
        decoding=false;
        if(data==null||c==null)return;
        try{
            Camera.Size z=c.getParameters().getPreviewSize();
            Result r=decode(data,z.width,z.height);
            if(r==null){byte[]rot=rotateY(data,z.width,z.height);r=decode(rot,z.height,z.width);}
            if(r!=null){
                Intent i=new Intent().putExtra(EXTRA_RESULT,r.getText());
                setResult(RESULT_OK,i);
                finish();
                return;
            }
        }catch(Exception ignored){}
        new Handler(Looper.getMainLooper()).postDelayed(this::scheduleFrame,120);
    }

    private Result decode(byte[]yuv,int w,int h){
        try{
            PlanarYUVLuminanceSource src=new PlanarYUVLuminanceSource(yuv,w,h,0,0,w,h,false);
            BinaryBitmap bmp=new BinaryBitmap(new HybridBinarizer(src));
            Map<DecodeHintType,Object>hints=new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.POSSIBLE_FORMATS,Arrays.asList(BarcodeFormat.QR_CODE,BarcodeFormat.CODE_128,BarcodeFormat.CODE_39,BarcodeFormat.DATA_MATRIX));
            return new MultiFormatReader().decode(bmp,hints);
        }catch(Exception e){return null;}
    }

    private byte[] rotateY(byte[]d,int w,int h){byte[]r=new byte[w*h];int k=0;for(int x=0;x<w;x++)for(int y=h-1;y>=0;y--)r[k++]=d[y*w+x];return r;}
    private void stopCamera(){if(camera!=null){try{camera.setPreviewCallback(null);camera.stopPreview();camera.release();}catch(Exception ignored){}camera=null;}decoding=false;}
    @Override protected void onPause(){stopCamera();super.onPause();}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
