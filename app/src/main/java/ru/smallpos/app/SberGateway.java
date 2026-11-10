package ru.smallpos.app;

import android.content.Context;
import android.net.Uri;
import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Sber e-commerce JSON API. A browser redirect is never evidence of payment. */
public final class SberGateway {
    public static final String LIVE="https://ecommerce.sberbank.ru/ecomm/gw/partner/api/v1/";
    public static final String TEST="https://ecomtest.sberbank.ru/ecomm/gw/partner/api/v1/";
    interface Transport {JSONObject post(String url,JSONObject body,String key)throws Exception;}
    public static final class VerifiedPayment {
        public final String bankId,number;
        public final long amount;
        public final boolean paid;
        private VerifiedPayment(String bankId,String number,long amount,boolean paid){this.bankId=bankId;this.number=number;this.amount=amount;this.paid=paid;}
    }
    public static final class Result {public String url,message;public boolean paid,cancelled;}
    private final RetailDb db;
    private final SecretStore secrets;
    private final Transport transport;
    public SberGateway(Context context,RetailDb db){this(context,db,SberGateway::postHttps);}
    SberGateway(Context context,RetailDb db,Transport transport){this.db=db;this.secrets=new SecretStore(context);this.transport=transport;}
    public boolean ready(){return db.enabled("bank_auto")&&secrets.present();}
    public void configure(boolean enabled,boolean test,String user,String password,String returnUrl)throws Exception{
        db.requireAdmin();if(!enabled){db.saveBankConfig(false,test,returnUrl);return;}
        if(user==null||!user.matches("[A-Za-z0-9_-]{1,30}")||password==null||password.length()<1||password.length()>36)throw new IllegalArgumentException("Введите API-логин и API-пароль, выданные банком.");
        validHttps(returnUrl);JSONObject s=new JSONObject().put("user",user).put("password",password);
        if(ready()&&db.bankPending()){
            JSONObject old=new JSONObject(secrets.read());
            if(test!=db.enabled("bank_test")||!user.equals(old.getString("user"))||!returnUrl.equals(db.setting("bank_return","")))throw new IllegalStateException("Есть незавершённые платежи. Можно обновить пароль того же банковского аккаунта; менять аккаунт и шлюз пока нельзя.");
            secrets.save(s.toString());return;
        }
        secrets.save(s.toString());db.saveBankConfig(true,test,returnUrl);
    }
    private JSONObject credentials()throws Exception{JSONObject s=new JSONObject(secrets.read());return new JSONObject().put("userName",s.getString("user")).put("password",s.getString("password"));}
    public Result refresh(long id)throws Exception{
        if(!ready())throw new IllegalStateException("Автоматическая оплата не подключена.");RetailDb.Order order=db.order(id);if(!"sbp".equals(order.method))throw new IllegalArgumentException("Для этого заказа выбран другой способ оплаты.");
        Result result=new Result();if("paid".equals(order.state)){result.paid=true;return result;}if(!"pending".equals(order.state))throw new IllegalStateException("Заказ отменён.");
        String number=db.bankNumber(id),bankId=db.bankId(id),url=db.bankUrl(id);String base=db.bankTest(id)?TEST:LIVE;
        if(bankId.isEmpty()){
            JSONObject request=credentials().put("orderNumber",number).put("amount",order.total).put("currency","643").put("returnUrl",db.setting("bank_return","")).put("language","ru").put("pageView","DESKTOP").put("sessionTimeoutSecs",1200).put("jsonParams",new JSONObject().put("qrType","DYNAMIC_QR_SBP").put("sbp.scenario","C2B"));
            JSONObject registered=transport.post(base+"register.do",request,number);
            if(!"0".equals(registered.optString("errorCode","0"))){
                // The unique merchant orderNumber prevents a second payment even
                // when registration succeeded but its HTTP response was lost.
                JSONObject old=transport.post(base+"getOrderStatusExtended.do",credentials().put("orderNumber",number),"");
                String recovered=old.optString("orderId","");org.json.JSONArray attrs=old.optJSONArray("attributes");if(attrs!=null)for(int i=0;i<attrs.length();i++)if("mdOrder".equals(attrs.getJSONObject(i).optString("name")))recovered=attrs.getJSONObject(i).getString("value");
                if(recovered.isEmpty())throw new IllegalStateException("Банк не создал платёж (код "+registered.optString("errorCode")+"). Обратитесь к продавцу; не оплачивайте повторно.");
                bankId=recovered;url=(db.bankTest(id)?"https://ecomtest.sberbank.ru":"https://ecommerce.sberbank.ru")+"/pp/pay_ru?orderId="+bankId;
            }else{bankId=registered.getString("orderId");url=registered.getString("formUrl");}
            validBankUrl(url);db.attachBank(id,bankId,url,number);
        }
        JSONObject status=transport.post(base+"getOrderStatusExtended.do",credentials().put("orderId",bankId),"");
        VerifiedPayment verified=verify(status,bankId,number,order.total);
        if(verified!=null){db.applyBankPayment(id,verified);result.paid=verified.paid;result.cancelled=!verified.paid;}else{result.url=url;int state=status.optInt("orderStatus",-1);result.message=state==4?"Платёж возвращён банком. Обратитесь к продавцу.":"Ожидаем подтверждение банка…";}
        return result;
    }
    static VerifiedPayment verify(JSONObject status,String bankId,String number,long total)throws Exception{
        if(!"0".equals(status.optString("errorCode","0")))throw new IllegalStateException("Не удалось проверить оплату в банке (код "+status.optString("errorCode")+").");
        int state=status.getInt("orderStatus");JSONObject amounts=status.optJSONObject("paymentAmountInfo");
        int outcome=Domain.bankOutcome(number,total,status.getString("orderNumber"),status.getLong("amount"),status.getString("currency"),state,amounts==null?"":amounts.optString("paymentState"),amounts==null?-1:amounts.optLong("depositedAmount",-1),amounts==null?-1:amounts.optLong("refundedAmount",-1));
        if(status.has("orderId")&&!bankId.equals(status.getString("orderId")))throw new IllegalStateException("Номер платежа не совпадает.");
        return outcome==0?null:new VerifiedPayment(bankId,number,total,outcome==1);
    }
    static void validHttps(String url){Uri u=Uri.parse(url);if(url.length()>2048||!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null)throw new IllegalArgumentException("Нужна HTTPS-ссылка возврата, согласованная с банком.");}
    static void validBankUrl(String url){validHttps(url);String host=Uri.parse(url).getHost().toLowerCase(java.util.Locale.ROOT);if(!host.endsWith(".sberbank.ru"))throw new IllegalArgumentException("Банк вернул неизвестный адрес оплаты.");}
    private static JSONObject postHttps(String url,JSONObject body,String key)throws Exception{
        HttpsURLConnection c=(HttpsURLConnection)new URL(url).openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");if(!key.isEmpty())c.setRequestProperty("x-idempotencyKey",key);
        try{try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}if(c.getResponseCode()!=200)throw new IOException("Банк недоступен (HTTP "+c.getResponseCode()+"). Повторите проверку этого заказа.");try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>1024*1024)throw new IOException("Слишком большой ответ банка.");out.write(buf,0,n);}return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));}}finally{c.disconnect();}
    }
}
