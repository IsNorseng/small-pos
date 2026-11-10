package ru.smallpos.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Merchant API credentials stay on the shop's device, encrypted with Android Keystore. */
final class SecretStore {
    private final Context context;
    SecretStore(Context context){this.context=context.getApplicationContext();}
    private SecretKey key()throws Exception{KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);String alias="small-pos-bank";if(!store.containsAlias(alias)){KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generator.generateKey();}return (SecretKey)store.getKey(alias,null);}
    void save(String value)throws Exception{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());String encrypted=Base64.encodeToString(c.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(c.doFinal(value.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);if(!context.getSharedPreferences("bank-secret",0).edit().putString("value",encrypted).commit())throw new IllegalStateException("Не удалось сохранить подключение банка.");}
    String read()throws Exception{String v=context.getSharedPreferences("bank-secret",0).getString("value","");if(v.isEmpty())return "";String[] parts=v.split(":",2);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.DEFAULT)));return new String(c.doFinal(Base64.decode(parts[1],Base64.DEFAULT)),StandardCharsets.UTF_8);}
    boolean present(){return !context.getSharedPreferences("bank-secret",0).getString("value","").isEmpty();}
}
