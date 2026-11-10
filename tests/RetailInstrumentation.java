package ru.smallpos.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.*;

/** Tests against Android SQLite and native views; no test framework dependency. */
public final class RetailInstrumentation extends Instrumentation {
    private int checks;
    private final StringBuilder report=new StringBuilder();
    interface Checked {void run()throws Exception;}
    private void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
    private void rejected(Checked action)throws Exception{checks++;try{action.run();}catch(IllegalArgumentException|IllegalStateException|SecurityException e){return;}throw new AssertionError("Expected rejection");}
    private Map<Long,Long> cart(long id,long q){Map<Long,Long> c=new LinkedHashMap<>();c.put(id,q);return c;}
    private RetailDb.Order create(RetailDb db,Map<Long,Long> c,String method){return db.createOrder(c,db.revision(),Domain.total(db.quote(c)),UUID.randomUUID().toString(),method);}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle out=new Bundle();try{
        dataTests();bankTests();uiTests();out.putString("stream","\nPASS: "+checks+" Android checks\n"+report);finish(Activity.RESULT_OK,out);
    }catch(Throwable e){out.putString("stream","\nFAIL after "+checks+" checks: "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,out);}}
    private void dataTests()throws Exception{
        Context context=getTargetContext();String name="instrumentation-"+UUID.randomUUID()+".sqlite";final RetailDb db=new RetailDb(context,name);
        try{
            check(!db.configured(),"first launch");rejected(()->db.saveProduct(0,"Тест","piece",100,true));
            rejected(()->db.setup("Точка","123456","123456","coffee"));
            db.setup("Точка","123456","654321","coffee");check(db.products(true).size()==10,"template");
            long id=db.products(false).get(0).id;Map<Long,Long> c=cart(id,2);long revision=db.revision(),total=Domain.total(db.quote(c));String key=UUID.randomUUID().toString();
            db.logout();check(db.role()==Domain.Role.CLIENT,"client role");rejected(()->db.stats(0));
            RetailDb.Order order=db.createOrder(c,revision,total,key,"cash");
            check(db.createOrder(c,revision,total,key,"cash").id==order.id,"double tap idempotent");
            rejected(()->db.createOrder(cart(id,3),revision,total,key,"cash"));rejected(()->db.confirmPaid(order.id,"cash"));rejected(()->db.cancel(order.id));
            check(!db.login(Domain.Role.ADMIN,"654321"),"seller PIN cannot admin");check(db.login(Domain.Role.SELLER,"654321"),"seller login");
            rejected(()->db.saveProduct(0,"Запрет","piece",100,true));rejected(()->db.exportBackup());
            db.confirmPaid(order.id,"cash");db.confirmPaid(order.id,"cash");rejected(()->db.confirmPaid(order.id,"sbp"));rejected(()->db.cancel(order.id));db.complete(order.id);
            check(db.orders(false,0).isEmpty(),"fulfilled removed");
            db.login(Domain.Role.ADMIN,"123456");JSONObject stats=db.stats(0);check(stats.getLong("sales")==1&&stats.getLong("revenue")==total,"only one paid sale");
            check(stats.getJSONArray("top").getJSONObject(0).getLong("orders")==1,"top counts orders");
            db.saveProduct(id,"Новая цена","piece",999,true);check(db.order(order.id).total==total&&db.order(order.id).lines.get(0).product.price==14000,"immutable price");
            rejected(()->db.createOrder(c,revision,total,UUID.randomUUID().toString(),"cash"));
            check(db.createOrder(c,revision,total,key,"cash").id==order.id,"retry after price change");
            rejected(()->db.saveSettings("Точка",false,false,false,"",""));rejected(()->db.saveSettings("Точка",true,true,false,"http://example.com",""));
            db.saveSettings("Точка",true,true,true,"https://example.com/pay","");
            RetailDb.Order pending=create(db,c,"sbp");check(db.stats(0).getLong("sales")==1,"pending excluded");db.cancel(pending.id);rejected(()->db.confirmPaid(pending.id,"sbp"));
            db.saveProduct(0,"Помидоры","kg",18000,true);long weight=db.products(true).get(10).id;
            db.logout();rejected(()->db.quote(cart(weight,650)));db.login(Domain.Role.SELLER,"654321");RetailDb.Order weighed=create(db,cart(weight,650),"terminal");check(weighed.total==11700,"grams round exactly");
            db.confirmPaid(weighed.id,"terminal");db.login(Domain.Role.ADMIN,"123456");check(db.stats(0).getLong("sales")==2,"second paid sale");
            JSONObject copy=db.exportBackup();check(!copy.has("users"),"credentials excluded");
            final JSONObject bad=new JSONObject(copy.toString());bad.getJSONArray("orders").getJSONObject(0).put("total",1);rejected(()->db.restoreBackup(bad));check(db.stats(0).getLong("sales")==2,"bad copy preserves DB");
            final JSONObject badLink=new JSONObject(copy.toString());badLink.getJSONObject("settings").put("sbp_link","javascript:alert(1)");rejected(()->db.restoreBackup(badLink));
            db.saveProduct(0,"Лишний","piece",100,true);db.restoreBackup(copy);check(db.products(true).size()==11,"backup restores products");check(db.stats(0).getLong("sales")==2,"backup restores sales");
            db.changePin(Domain.Role.SELLER,"123456","998877");db.logout();check(!db.login(Domain.Role.SELLER,"654321"),"old PIN invalid");check(db.login(Domain.Role.SELLER,"998877"),"new PIN valid");
            for(int i=0;i<105;i++)db.confirmPaid(create(db,c,"cash").id,"cash");List<RetailDb.Order> first=db.orders(false,0);check(first.size()==100,"queue page size");check(db.orders(false,first.get(99).id).size()==6,"queue next page, no lost order");
            db.saveDraft(cart(id,3));db.close();final RetailDb reopened=new RetailDb(context,name);try{
            check(reopened.role()==Domain.Role.CLIENT,"restart logs out");check(reopened.draft().get(id)==3L,"draft survives");check(reopened.configured(),"setup survives");reopened.login(Domain.Role.ADMIN,"123456");check(reopened.stats(0).getLong("sales")==107,"sales survive reopen");
            reopened.changePin(Domain.Role.ADMIN,"123456","112233");check(reopened.role()==Domain.Role.CLIENT,"admin PIN change logs out");
            check(!reopened.login(Domain.Role.ADMIN,"123456"),"old admin PIN invalid");check(reopened.login(Domain.Role.ADMIN,"112233"),"new admin PIN");reopened.logout();
            for(int i=0;i<5;i++)check(!reopened.login(Domain.Role.SELLER,"000000"),"invalid PIN");rejected(()->reopened.login(Domain.Role.SELLER,"998877"));
            }finally{reopened.close();}
            report.append("SQLite: roles, prices, cash/SBP/terminal, weights, duplicate requests, pagination, backup, restart, PIN lock.\n");
        }finally{db.close();context.deleteDatabase(name);}
    }
    private void bankTests()throws Exception{
        Context context=getTargetContext();String name="bank-test-"+UUID.randomUUID()+".sqlite";final RetailDb db=new RetailDb(context,name);
        final int[] status={0},registered={0};final long[] wrongAmount={0};final String[] number={""};final String[] bankId={UUID.randomUUID().toString()};
        SberGateway.Transport transport=(url,body,key)->{
            if(url.endsWith("register.do")){registered[0]++;bankId[0]=UUID.randomUUID().toString();number[0]=body.getString("orderNumber");check(key.equals(number[0]),"registration idempotency header");check(body.getJSONObject("jsonParams").getString("qrType").equals("DYNAMIC_QR_SBP"),"dynamic SBP");return new JSONObject().put("errorCode","0").put("orderId",bankId[0]).put("formUrl","https://ecomtest.sberbank.ru/pp/pay_ru?orderId="+bankId[0]);}
            RetailDb.Order o=db.order(db.currentOrder());return new JSONObject().put("errorCode","0").put("orderNumber",number[0]).put("orderId",bankId[0]).put("amount",o.total+wrongAmount[0]).put("currency","643").put("orderStatus",status[0]).put("paymentAmountInfo",new JSONObject().put("paymentState","DEPOSITED").put("depositedAmount",o.total).put("refundedAmount",0));
        };
        try{
            db.setup("Банк тест","123456","654321","coffee");SberGateway gateway=new SberGateway(context,db,transport);
            gateway.configure(true,true,"test_user","test_password","https://example.com/return");check(gateway.ready(),"encrypted credentials ready");db.saveSettings("Банк тест",false,true,false,"","");
            Map<Long,Long> c=cart(db.products(false).get(0).id,1);db.logout();RetailDb.Order o=create(db,c,"sbp");
            check(!gateway.refresh(o.id).paid,"pending bank response not paid");check(db.order(o.id).state.equals("pending"),"awaits bank");
            db.login(Domain.Role.ADMIN,"123456");rejected(()->db.confirmPaid(o.id,"sbp"));rejected(()->db.cancel(o.id));rejected(()->gateway.configure(false,true,"","",""));rejected(()->db.restoreBackup(db.exportBackup()));db.logout();
            status[0]=2;wrongAmount[0]=1;rejected(()->gateway.refresh(o.id));check(db.order(o.id).state.equals("pending"),"wrong amount rejected");wrongAmount[0]=0;
            check(gateway.refresh(o.id).paid,"automatic bank confirmation in client role");check(registered[0]==1,"refresh never re-registers");check(gateway.refresh(o.id).paid,"repeat response safe");
            db.login(Domain.Role.ADMIN,"123456");check(db.stats(0).getLong("sales")==0,"bank sandbox excluded from sales");check(db.stats(0).getJSONArray("top").length()==0,"sandbox excluded from popularity");check(db.orders(false,0).size()==1,"paid assembly queue");db.complete(o.id);
            db.saveBehavior(true,true);db.logout();status[0]=0;RetailDb.Order self=create(db,c,"sbp");gateway.refresh(self.id);status[0]=2;gateway.refresh(self.id);check(db.order(self.id).done,"self-service completes without seller");
            db.login(Domain.Role.ADMIN,"123456");JSONObject copy=db.exportBackup();db.restoreBackup(copy);check(db.hasBankPayment(self.id),"bank identity preserved in backup");check(!gateway.ready(),"restore disables bank until reconnected");gateway.configure(true,true,"test_user","test_password","https://example.com/return");
            status[0]=0;RetailDb.Order declined=create(db,c,"sbp");gateway.refresh(declined.id);status[0]=6;check(gateway.refresh(declined.id).cancelled,"bank rejection closes order");check(!db.bankPending(),"failed bank order does not block setup forever");
            JSONObject paid=transport.post("getOrderStatusExtended.do",new JSONObject(),"");paid.put("orderStatus",2);paid.getJSONObject("paymentAmountInfo").put("refundedAmount",1);rejected(()->SberGateway.verify(paid,bankId[0],number[0],declined.total));
            report.append("Sber contract fixtures: encrypted configuration, dynamic SBP, matching number/amount/currency, pending, rejection, repeat polling, client confirmation, self-service, sandbox stats, restored bank IDs. Live acquiring not exercised.\n");
        }finally{db.close();context.deleteDatabase(name);}
    }
    private View find(View root,String text,boolean button){if(root instanceof TextView&&(!button||root instanceof Button)&&((TextView)root).getText().toString().equals(text))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View v=find(((ViewGroup)root).getChildAt(i),text,button);if(v!=null)return v;}return null;}
    private void edits(View v,List<EditText> list){if(v instanceof EditText)list.add((EditText)v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)edits(((ViewGroup)v).getChildAt(i),list);}
    private void uiTests()throws Exception{
        // Run on a fresh test emulator only: the UI scenario uses the app's real local database.
        Intent intent=new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);final Activity activity=startActivitySync(intent);waitForIdleSync();
        runOnMainSync(()->{
            View root=activity.getWindow().getDecorView();List<EditText> e=new ArrayList<>();edits(root,e);
            check(e.size()==3,"setup fields visible");e.get(0).setText("Тестовая кофейня");e.get(1).setText("123456");e.get(2).setText("654321");find(root,"Начать работу",true).performClick();
        });waitForIdleSync();
        runOnMainSync(()->{View root=activity.getWindow().getDecorView();check(find(root,"Товары и цены",true)!=null,"admin settings opened");find(root,"Перейти к клиенту",true).performClick();});waitForIdleSync();
        runOnMainSync(()->{View root=activity.getWindow().getDecorView();check(find(root,"Режим: клиент",false)!=null,"client visible");check(find(root,"Подтвердить получение оплаты",true)==null,"client no paid control");find(root,"Добавить",true).performClick();});waitForIdleSync();
        runOnMainSync(()->{View root=activity.getWindow().getDecorView();check(find(root,"Оформить заказ",true)!=null,"cart and checkout rendered");});
        runOnMainSync(activity::finish);waitForIdleSync();
        final Activity restarted=startActivitySync(intent);waitForIdleSync();
        runOnMainSync(()->{View root=restarted.getWindow().getDecorView();check(find(root,"Режим: клиент",false)!=null,"UI restart safe");check(find(root,"Оформить заказ",true)!=null,"cart restored in UI");restarted.finish();});
        report.append("Native UI: first setup, admin → client, add product, durable cart after activity restart.\n");
    }
}
