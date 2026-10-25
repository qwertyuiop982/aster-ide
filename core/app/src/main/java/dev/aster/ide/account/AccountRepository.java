package dev.aster.ide.account;

import android.content.Context;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;

public final class AccountRepository {
    public static final String SERVICE="https://asteride.cn/api/v1";
    public static final String FREE_ENDPOINT=SERVICE+"/free";
    public static final String GO_ENDPOINT=SERVICE+"/free-go";
    private final AccountVault vault;
    private static volatile AccountRepository instance;
    private AccountRepository(Context context){vault=new AccountVault(context.getApplicationContext());}
    public static AccountRepository get(Context context){AccountRepository value=instance;if(value==null)synchronized(AccountRepository.class){value=instance;if(value==null)instance=value=new AccountRepository(context);}return value;}
    public boolean loggedIn(){try{return !vault.get("session").isEmpty();}catch(Exception error){return false;}}
    public String freeToken()throws Exception{String token=vault.get("session");if(token.isEmpty())throw new IOException("请先打开账户页面登录，再使用 Aster-free");return token;}
    public void requireFreeAvailable(String tier)throws Exception{
        JSONObject capabilities=request("GET","/health",null,false).getJSONObject("capabilities");
        String label="go".equals(tier)?"Aster-free go":"Aster-free";
        if(!capabilities.optBoolean("go".equals(tier)?"free_go":"free"))throw new IOException(label+" 暂未开放，请在账户页查看服务状态，或切换 Pi 为自定义 API。");
    }
    public JSONObject state()throws Exception{
        JSONObject result=new JSONObject().put("logged_in",false);JSONObject health=request("GET","/health",null,false);result.put("capabilities",health.getJSONObject("capabilities"));
        if(loggedIn())try{result.put("identity",request("GET","/account/me",null,true));result.put("wallet",request("GET","/wallet",null,true));result.put("logged_in",true);}catch(ApiFailure error){if(error.status==401)vault.put("session","");else throw error;}return result;
    }
    public String githubStart()throws Exception{
        byte[] random=new byte[32];new SecureRandom().nextBytes(random);String verifier=AccountVault.encode(random);vault.put("login_verifier",verifier);
        String challenge=AccountVault.encode(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        JSONObject reply=request("POST","/auth/github/start",new JSONObject().put("code_challenge",challenge),false);String address=reply.getString("authorize_url");Uri uri=Uri.parse(address);
        if(!"https".equals(uri.getScheme())||!"github.com".equals(uri.getHost())||!"/login/oauth/authorize".equals(uri.getPath()))throw new IOException("GitHub 授权地址无效");return address;
    }
    public void exchange(Uri uri)throws Exception{
        boolean allowed=("https".equals(uri.getScheme())&&"asteride.cn".equals(uri.getHost())&&"/auth/complete".equals(uri.getPath()))||("asteraccount".equals(uri.getScheme())&&"login".equals(uri.getHost()));
        if(!allowed)throw new IOException("登录回调地址无效");String code=uri.getQueryParameter("code"),verifier=vault.get("login_verifier");
        if(code==null||!code.matches("[A-Za-z0-9_-]{43}")||verifier.isEmpty())throw new IOException("请从 Aster 账户页重新发起登录");
        JSONObject reply=request("POST","/auth/exchange",new JSONObject().put("code",code).put("code_verifier",verifier),false);vault.put("session",reply.getString("access_token"));vault.put("login_verifier","");
    }
    public JSONObject emailStart(String email)throws Exception{return request("POST","/auth/email/start",new JSONObject().put("email",email.trim()),false);}
    public void emailVerify(String id,String code)throws Exception{JSONObject reply=request("POST","/auth/email/verify",new JSONObject().put("challenge_id",id).put("code",code.trim()),false);vault.put("session",reply.getString("access_token"));}
    public JSONObject checkin()throws Exception{return request("POST","/checkins",new JSONObject(),true);}
    public JSONObject ledger()throws Exception{return request("GET","/wallet/ledger",null,true);}
    public void logout()throws Exception{if(loggedIn())request("POST","/auth/logout",new JSONObject(),true);vault.put("session","");}
    private JSONObject request(String method,String path,JSONObject body,boolean authenticated)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new URL(SERVICE+path).openConnection();connection.setConnectTimeout(12000);connection.setReadTimeout(30000);connection.setInstanceFollowRedirects(false);connection.setRequestMethod(method);connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("User-Agent","Aster-Account/1.0.1");
        if(authenticated)connection.setRequestProperty("Authorization","Bearer "+freeToken());
        try{
            if(body!=null){connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");try(OutputStream output=connection.getOutputStream()){output.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            int status=connection.getResponseCode();InputStream input=status<400?connection.getInputStream():connection.getErrorStream();if(input==null)throw new ApiFailure(status,"服务未返回数据");ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream in=input){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>2*1024*1024)throw new IOException("响应超过大小限制");bytes.write(buffer,0,n);}}
            JSONObject result;try{result=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));}catch(JSONException error){throw new ApiFailure(status,"服务响应格式无效");}
            if(status<200||status>=300){JSONObject error=result.optJSONObject("error");throw new ApiFailure(status,error==null?"请求失败，HTTP "+status:error.optString("message","请求失败"));}return result;
        }finally{connection.disconnect();}
    }
    public static final class ApiFailure extends IOException{public final int status;ApiFailure(int status,String message){super(message);this.status=status;}}
}