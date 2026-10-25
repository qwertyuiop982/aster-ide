package dev.aster.ide.account;
import android.content.Context;
import android.security.keystore.*;
import android.util.Base64;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

public final class AccountVault {
    private final Context context;
    public AccountVault(Context context){this.context=context;}
    private SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        String alias="aster.account.storage.v1";
        if(!store.containsAlias(alias)){
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey)store.getKey(alias,null);
    }
    public static String encode(byte[] bytes){return Base64.encodeToString(bytes,Base64.URL_SAFE|Base64.NO_PADDING|Base64.NO_WRAP);}
    public static byte[] decode(String text){return Base64.decode(text,Base64.URL_SAFE|Base64.NO_PADDING|Base64.NO_WRAP);}
    public synchronized String get(String name) throws Exception {
        String raw=context.getSharedPreferences("vault",Context.MODE_PRIVATE).getString(name,"");if(raw.isEmpty())return "";
        String[] fields=raw.split("\\.",2);if(fields.length!=2)throw new IllegalStateException("凭据存储格式无效");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,decode(fields[0])));cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(decode(fields[1])),StandardCharsets.UTF_8);
    }
    public synchronized void put(String name,String value)throws Exception{
        android.content.SharedPreferences.Editor edit=context.getSharedPreferences("vault",Context.MODE_PRIVATE).edit();
        if(value.isEmpty()){if(!edit.remove(name).commit())throw new IllegalStateException("凭据保存失败");return;}
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
        String raw=encode(cipher.getIV())+"."+encode(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        if(!edit.putString(name,raw).commit())throw new IllegalStateException("凭据保存失败");
    }
}
