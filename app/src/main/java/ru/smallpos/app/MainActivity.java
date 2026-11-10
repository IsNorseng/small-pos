package ru.smallpos.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Small native interface. All business rules and permissions live in RetailDb. */
public final class MainActivity extends Activity {
    private static final int BLUE=0xff244de7, INK=0xff172033, MUTED=0xff586174, BG=0xfff4f6fa;
    private static final int QR=11, EXPORT=12, IMPORT=13;
    private RetailDb db;
    private SberGateway gateway;
    private final java.util.concurrent.ExecutorService bankWorker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private long screenOrder;
    private int screenVersion;
    private boolean bankBusy;
    private android.webkit.WebView paymentView;
    private boolean queueScreen;
    private boolean queueHistory;
    private long queueCursor,monitorCursor;
    private LinearLayout body, root;
    private Map<Long,Long> cart;
    private long backgroundAt, lastTouch;
    private String exportText;
    private final Handler timer=new Handler(Looper.getMainLooper());
    private final List<AlertDialog> dialogs=new ArrayList<>();
    private final Runnable idle=new Runnable(){public void run(){
        if(db!=null && db.role()!=Domain.Role.CLIENT && System.currentTimeMillis()-lastTouch>180000){lock();}
        timer.postDelayed(this,30000);
    }};
    private final Runnable monitor=new Runnable(){public void run(){
        if(db!=null&&gateway.ready()&&!bankBusy&&screenOrder==0&&!isFinishing()){
            bankBusy=true;final int version=screenVersion;
            bankWorker.execute(()->{boolean changed=false;try{List<Long> ids=db.pendingBankIds(monitorCursor);if(ids.isEmpty()){monitorCursor=0;ids=db.pendingBankIds(0);}for(long id:ids){monitorCursor=id;SberGateway.Result r=gateway.refresh(id);changed|=r.paid||r.cancelled;}}catch(Exception ignored){}final boolean updated=changed;runOnUiThread(()->{bankBusy=false;if(updated&&queueScreen&&version==screenVersion&&!isFinishing()&&!isDestroyed())attempt(()->queue(queueHistory,queueCursor));});});
        }
        timer.postDelayed(this,10000);
    }};
    interface Action {void run() throws Exception;}
    private void attempt(Action action){try{action.run();}catch(Exception e){message(e instanceof org.json.JSONException?"Файл повреждён или имеет другой формат.":e.getMessage());}}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void message(String text){new AlertDialog.Builder(this).setTitle("Проверьте данные").setMessage(text==null?"Не удалось выполнить действие.":text).setPositiveButton("Понятно",null).show();}
    private AlertDialog show(AlertDialog.Builder builder){AlertDialog d=builder.create();dialogs.add(d);d.setOnDismissListener(x->dialogs.remove(d));d.show();return d;}
    @Override public void onCreate(Bundle state){super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        db=new RetailDb(this);gateway=new SberGateway(this,db);cart=db.draft();lastTouch=System.currentTimeMillis();
        attempt(()->{if(db.configured())catalog();else setup();});timer.postDelayed(idle,30000);timer.postDelayed(monitor,10000);
    }
    @Override public void onUserInteraction(){super.onUserInteraction();lastTouch=System.currentTimeMillis();}
    @Override protected void onPause(){super.onPause();backgroundAt=System.currentTimeMillis();}
    @Override protected void onResume(){super.onResume();if(db!=null&&backgroundAt>0&&System.currentTimeMillis()-backgroundAt>60000&&db.role()!=Domain.Role.CLIENT)lock();}
    @Override protected void onDestroy(){timer.removeCallbacksAndMessages(null);bankWorker.shutdown();new Thread(()->{try{bankWorker.awaitTermination(60,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException ignored){}db.close();},"database-close").start();super.onDestroy();}
    @Override public void onBackPressed(){attempt(()->{if(!db.configured())super.onBackPressed();else if(db.role()==Domain.Role.ADMIN)admin();else catalog();});}
    private void lock(){for(AlertDialog d:new ArrayList<>(dialogs))d.dismiss();db.logout();cart=db.draft();attempt(()->{if(db.configured())catalog();else setup();});}

    private void page(String title){
        if(paymentView!=null){paymentView.stopLoading();if(paymentView.getParent() instanceof ViewGroup)((ViewGroup)paymentView.getParent()).removeView(paymentView);paymentView.destroy();paymentView=null;}screenOrder=0;screenVersion++;queueScreen=false;
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);root.setFitsSystemWindows(true);
        root.setOnApplyWindowInsetsListener((view,insets)->{view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets.consumeSystemWindowInsets();});
        TextView heading=label(title,22,INK);heading.setTypeface(null,Typeface.BOLD);heading.setPadding(dp(20),dp(16),dp(20),dp(8));root.addView(heading);
        if(db.configured()){
            LinearLayout bar=new LinearLayout(this);bar.setPadding(dp(12),0,dp(12),dp(4));
            button(bar,db.role()==Domain.Role.CLIENT?"Вход сотрудника":"Клиент",()->{if(db.role()==Domain.Role.CLIENT)login();else lock();},false);
            if(db.role()!=Domain.Role.CLIENT)button(bar,"Заказы",()->queue(false,0),false);
            if(db.role()==Domain.Role.ADMIN)button(bar,"Настройки",this::admin,false);
            root.addView(bar);TextView role=label("Режим: "+roleName(db.role()),13,MUTED);role.setPadding(dp(20),0,dp(20),dp(4));root.addView(role);
        }
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(16),dp(8),dp(16),dp(28));scroll.addView(body);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
    }
    private String roleName(Domain.Role role){return role==Domain.Role.ADMIN?"администратор":role==Domain.Role.SELLER?"продавец":"клиент";}
    private TextView label(String text,int size,int color){TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(4),0,dp(6));return t;}
    private void text(String text){body.addView(label(text,16,INK));}
    private void note(String text){body.addView(label(text,14,MUTED));}
    private void title(String text){TextView t=label(text,20,INK);t.setTypeface(null,Typeface.BOLD);body.addView(t);}
    private Button button(LinearLayout parent,String text,Action action,boolean primary){
        Button b=new Button(this);b.setText(text);b.setTextSize(15);b.setAllCaps(false);b.setMinHeight(dp(52));b.setTextColor(primary?Color.WHITE:BLUE);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary?BLUE:0xffe7edff));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(parent.getOrientation()==LinearLayout.HORIZONTAL?0:-1,-2,parent.getOrientation()==LinearLayout.HORIZONTAL?1:0);p.setMargins(dp(3),dp(4),dp(3),dp(4));parent.addView(b,p);
        b.setOnClickListener(v->attempt(action));return b;
    }
    private Button button(String text,Action action){return button(body,text,action,false);}
    private EditText field(LinearLayout parent,String label,String value,int input){parent.addView(this.label(label,14,MUTED));EditText e=new EditText(this);e.setText(value);e.setHint(label);e.setTextSize(17);e.setSingleLine(true);e.setInputType(input);e.setMinHeight(dp(52));parent.addView(e,new LinearLayout.LayoutParams(-1,-2));return e;}
    private EditText field(String label,String value,int input){return field(body,label,value,input);}
    private EditText pin(LinearLayout parent,String label){return field(parent,label,"",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);}
    private Spinner select(LinearLayout parent,String label,String[] choices,int selected){parent.addView(this.label(label,14,MUTED));Spinner s=new Spinner(this);ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,choices);s.setAdapter(a);s.setSelection(selected);s.setMinimumHeight(dp(52));parent.addView(s);return s;}
    private CheckBox check(String label,boolean checked){CheckBox c=new CheckBox(this);c.setText(label);c.setTextSize(16);c.setChecked(checked);c.setMinHeight(dp(48));body.addView(c);return c;}
    private LinearLayout form(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(20),dp(4),dp(20),dp(12));return l;}

    private void setup(){page("Малая касса");title("Настройка за пару минут");note("Всё хранится на этом устройстве. Без аккаунта, сервера и подписки.");
        EditText name=field("Название точки","Малая касса",InputType.TYPE_CLASS_TEXT);
        EditText adminPin=pin(body,"PIN администратора · 6–12 цифр"),sellerPin=pin(body,"PIN продавца · другой, 6–12 цифр");
        Spinner template=select(body,"Начальный каталог",new String[]{"Кофейня · 10 позиций","Овощи и фрукты · 10 позиций","Чебуречная · 10 позиций","Пустой каталог"},0);
        note("Цены в примерах временные. Измените их перед продажами. Сохраните PIN-коды: восстановление через интернет не предусмотрено.");
        button(body,"Начать работу",()->{db.setup(name.getText().toString(),adminPin.getText().toString(),sellerPin.getText().toString(),new String[]{"coffee","produce","food","empty"}[template.getSelectedItemPosition()]);admin();},true);
    }
    private void login(){LinearLayout l=form();Spinner role=select(l,"Доступ",new String[]{"Продавец","Администратор"},0);EditText pin=pin(l,"PIN-код");
        AlertDialog d=show(new AlertDialog.Builder(this).setTitle("Вход сотрудника").setView(l).setNegativeButton("Отмена",null).setPositiveButton("Войти",null));
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->attempt(()->{
            if(!db.login(role.getSelectedItemPosition()==0?Domain.Role.SELLER:Domain.Role.ADMIN,pin.getText().toString())){pin.setText("");pin.setError("Неверный PIN-код");return;}
            lastTouch=System.currentTimeMillis();d.dismiss();if(db.role()==Domain.Role.ADMIN)admin();else queue(false,0);
        }));
    }
    private void catalog(){
        if(db.role()==Domain.Role.CLIENT){Map<Long,Domain.Product> all=new HashMap<>();for(Domain.Product p:db.products(true))all.put(p.id,p);cart.entrySet().removeIf(e->all.get(e.getKey())==null||!all.get(e.getKey()).active||("kg".equals(all.get(e.getKey()).unit)&&!db.enabled("self_weigh")));db.saveDraft(cart);}
        page(db.setting("name","Малая касса"));
        long current=db.currentOrder();if(current>0)button("Мой заказ №"+current,()->orderScreen(current));
        title(db.role()==Domain.Role.CLIENT?"Выберите покупку":"Новая продажа");
        if(db.role()==Domain.Role.CLIENT)note(db.enabled("self_serve")?"Возьмите товары, укажите их в корзине и оплатите покупку.":"Выберите и оплатите. Продавец соберёт ваш заказ по его номеру.");
        List<Domain.Product> products=db.products(false);if(products.isEmpty())note("Каталог пока пуст. Администратор может добавить товары в настройках.");
        int columns=getResources().getConfiguration().screenWidthDp>=700?3:getResources().getConfiguration().screenWidthDp>=380?2:1;
        LinearLayout row=null;
        for(int i=0;i<products.size();i++){
            if(i%columns==0){row=new LinearLayout(this);row.setBaselineAligned(false);body.addView(row);}
            Domain.Product p=products.get(i);LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(12),dp(10),dp(12),dp(10));card.setBackgroundColor(Color.WHITE);
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,1);cp.setMargins(dp(4),dp(4),dp(4),dp(4));row.addView(card,cp);
            TextView n=label(p.name,18,INK);n.setMinLines(2);n.setTypeface(null,Typeface.BOLD);card.addView(n);card.addView(label(Domain.money(p.price)+("kg".equals(p.unit)?" / кг":" / шт."),16,MUTED));
            boolean weigh="kg".equals(p.unit),blocked=weigh&&db.role()==Domain.Role.CLIENT&&!db.enabled("self_weigh");String action=weigh?(blocked?"У продавца":"Указать вес"):"Добавить";
            button(card,action,()->{if(blocked)message("Для весовых товаров нужны весы. Передайте товар продавцу либо используйте фасованный товар из каталога.");else if(weigh)quantityDialog(p);else {long q=cart.getOrDefault(p.id,0L)+1;Domain.amount(p.price,q,p.unit);cart.put(p.id,q);db.saveDraft(cart);catalog();}},false);
        }
        if(row!=null){int remainder=products.size()%columns;for(int i=remainder;i>0&&i<columns;i++)row.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));}
        title("Корзина");
        if(cart.isEmpty()){note("Добавьте нужные позиции.");return;}
        List<Domain.Line> lines;
        try{lines=db.quote(cart);}catch(Exception e){note(e.getMessage());button("Очистить корзину",()->{cart.clear();db.saveDraft(cart);catalog();});return;}
        for(Domain.Line line:lines){
            text(line.product.name+" · "+Domain.quantity(line.quantity,line.product.unit)+" · "+Domain.money(line.amount));
            LinearLayout controls=new LinearLayout(this);body.addView(controls);
            button(controls,"Изменить",()->quantityDialog(line.product),false);
            button(controls,"Убрать",()->{cart.remove(line.product.id);db.saveDraft(cart);catalog();},false);
        }
        long total=Domain.total(lines);title("Итого "+Domain.money(total));
        button(body,"Оформить заказ",this::checkout,true);
        button("Очистить корзину",()->{cart.clear();db.saveDraft(cart);catalog();});
    }
    private void quantityDialog(Domain.Product p){
        LinearLayout l=form();boolean weight="kg".equals(p.unit);long current=cart.getOrDefault(p.id,weight?1000L:1L);
        String value=weight?java.math.BigDecimal.valueOf(current,3).stripTrailingZeros().toPlainString():Long.toString(current);
        if(weight)l.addView(label("Введите вес с ваших весов. Например, 0,650 кг. Автоматического подключения весов нет.",14,MUTED));
        EditText quantity=field(l,weight?"Вес, кг · до 3 знаков после запятой":"Количество, шт.",value,InputType.TYPE_CLASS_NUMBER|(weight?InputType.TYPE_NUMBER_FLAG_DECIMAL:0));
        AlertDialog d=show(new AlertDialog.Builder(this).setTitle(p.name).setView(l).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null));
        d.getButton(-1).setOnClickListener(v->attempt(()->{if(weight&&!db.enabled("self_weigh"))db.requireSeller();long q=Domain.parseQuantity(quantity.getText().toString(),p.unit);Domain.amount(p.price,q,p.unit);cart.put(p.id,q);db.saveDraft(cart);d.dismiss();catalog();}));
    }
    private String methodName(String method){return "cash".equals(method)?"Наличные":"sbp".equals(method)?"СБП":"Терминал / другое приложение";}
    private void checkout(){
        List<Domain.Line> lines=db.quote(cart);long total=Domain.total(lines),revision=db.revision();String key=UUID.randomUUID().toString();
        List<String> methods=new ArrayList<>(),names=new ArrayList<>();for(String m:gateway.ready()?new String[]{"sbp","cash","terminal"}:new String[]{"cash","sbp","terminal"})if(db.enabled(m)){methods.add(m);names.add(methodName(m));}
        if(methods.isEmpty())throw new IllegalStateException("Администратор должен включить способ оплаты.");
        LinearLayout l=form();l.addView(label("К оплате "+Domain.money(total),22,INK));Spinner method=select(l,"Способ оплаты",names.toArray(new String[0]),0);
        l.addView(label(gateway.ready()?"Для СБП подтверждение придёт от банка. Остальные способы проверяет продавец.":"Сейчас оплата проверяется продавцом. Автоматическое подтверждение СБП включается в настройках банка.",14,MUTED));
        AlertDialog d=show(new AlertDialog.Builder(this).setTitle("Оформить заказ").setView(l).setNegativeButton("Назад",null).setPositiveButton("Создать заказ",null));
        d.getButton(-1).setOnClickListener(v->{d.getButton(-1).setEnabled(false);attempt(()->{RetailDb.Order order=db.createOrder(new LinkedHashMap<>(cart),revision,total,key,methods.get(method.getSelectedItemPosition()));cart.clear();d.dismiss();orderScreen(order.id);});if(d.isShowing())d.getButton(-1).setEnabled(true);});
    }
    private void orderScreen(long id){
        RetailDb.Order order=db.order(id);if(db.role()==Domain.Role.CLIENT&&db.currentOrder()!=id)throw new SecurityException("Этот заказ открывается у продавца.");
        page("Заказ №"+id);title(Domain.money(order.total));text(status(order));
        screenOrder=id;
        if((db.hasBankPayment(id)&&db.bankTest(id))||("sbp".equals(order.method)&&gateway.ready()&&db.enabled("bank_test")))note("ТЕСТ БАНКА · деньги не принимаются, в статистику продаж не входит.");
        note(new SimpleDateFormat("dd.MM.yyyy HH:mm",new Locale("ru")).format(new Date(order.created)));
        for(Domain.Line line:order.lines)text(line.product.name+" · "+Domain.quantity(line.quantity,line.product.unit)+" · "+Domain.money(line.amount));
        text("Оплата: "+methodName(order.method));
        if("pending".equals(order.state)){
            note("Оплата ещё не подтверждена.");
            if("cash".equals(order.method))text("Передайте продавцу "+Domain.money(order.total)+" и назовите номер заказа.");
            if("sbp".equals(order.method)&&gateway.ready()){
                note("Сканируйте QR на банковской странице своим телефоном. Сумма уже указана. Дождитесь подтверждения на этой кассе.");
                TextView bankStatus=label("Подключаемся к банку…",16,MUTED);body.addView(bankStatus);
                android.webkit.WebView web=new android.webkit.WebView(this);paymentView=web;android.webkit.CookieManager.getInstance().removeAllCookies(null);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);web.getSettings().setDomStorageEnabled(true);web.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);web.setWebViewClient(new android.webkit.WebViewClient(){@Override public boolean shouldOverrideUrlLoading(android.webkit.WebView v,android.webkit.WebResourceRequest r){Uri uri=r.getUrl();String scheme=uri.getScheme();if(uri.toString().startsWith(db.setting("bank_return","https://invalid.local")))return true;if("https".equals(scheme))return false;if("sbp".equals(scheme)||"sberpay".equals(scheme)||(scheme!=null&&scheme.matches("bank[0-9]{12}"))){attempt(()->startActivity(new Intent(Intent.ACTION_VIEW,uri)));return true;}if("intent".equals(scheme)){attempt(()->{Intent i=Intent.parseUri(uri.toString(),Intent.URI_INTENT_SCHEME);Uri target=i.getData();String sc=target==null?null:target.getScheme();if(target==null||!("qr.nspk.ru".equals(target.getHost())||"sberpay".equals(sc)))throw new IllegalArgumentException("Используйте QR-код для оплаты со своего телефона.");i.setComponent(null);i.setSelector(null);i.addCategory(Intent.CATEGORY_BROWSABLE);startActivity(i);});}return true;}});body.addView(web,new LinearLayout.LayoutParams(-1,dp(480)));
                int version=screenVersion;refreshBank(id,version,web,bankStatus);
                button("Проверить оплату в банке",()->refreshBank(id,version,web,bankStatus));
            }else if("sbp".equals(order.method)){
                String code=db.setting("sbp_image","");if(!code.isEmpty()){byte[] bytes=Base64.decode(code,Base64.DEFAULT);Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);if(bitmap!=null){ImageView image=new ImageView(this);image.setImageBitmap(bitmap);image.setAdjustViewBounds(true);image.setContentDescription("Банковский QR-код для оплаты СБП");body.addView(image,new LinearLayout.LayoutParams(-1,dp(300)));}}
                note("Проверьте получателя. Введите точную сумму "+Domain.money(order.total)+". Покажите результат продавцу: он проверит поступление в банке.");
                String link=db.setting("sbp_link","");if(!link.isEmpty())button("Открыть оплату СБП",()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(link))));
            }
            if("terminal".equals(order.method)){
                note("Оплатите "+Domain.money(order.total)+" у продавца через подключённый терминал или его платёжное приложение.");
                if(db.role()!=Domain.Role.CLIENT&&!db.setting("payment_package","").isEmpty())button("Открыть платёжное приложение",()->openPaymentApp(order));
            }
            if(db.role()!=Domain.Role.CLIENT&&!db.hasBankPayment(id)&&!("sbp".equals(order.method)&&gateway.ready())){
                button(body,"Подтвердить получение оплаты",()->confirmPayment(order),true);
                button("Отменить неоплаченный заказ",()->show(new AlertDialog.Builder(this).setTitle("Отменить заказ №"+id+"?").setMessage("Используйте это действие, только если деньги не поступили.").setNegativeButton("Назад",null).setPositiveButton("Отменить заказ",(d,w)->attempt(()->{db.cancel(id);queue(false,0);})))) ;
            }
        }else if("paid".equals(order.state)){text(order.selfServe?"Покупка оплачена. Спасибо!":"Покупка оплачена. Продавец соберёт заказ №"+id+".");if(!order.done&&db.role()!=Domain.Role.CLIENT)button(body,"Покупка выдана",()->{db.complete(id);queue(false,0);},true);}
        button("Обновить статус",()->orderScreen(id));
        button("Новая покупка",()->{db.clearCurrentOrder();catalog();});
        if(db.role()!=Domain.Role.CLIENT)button("Вернуться к заказам",()->queue(false,0));
        note("Экран заказа — запись для учёта. Фискальный чек оформляется отдельно.");
    }
    private String status(RetailDb.Order o){return "cancelled".equals(o.state)?"Заказ отменён":"pending".equals(o.state)?"Ожидает оплаты":o.done?"Оплачен · выдан":"Оплачен · к выдаче";}
    private void confirmPayment(RetailDb.Order order){db.requireSeller();LinearLayout l=form();
        l.addView(label("Получено "+Domain.money(order.total)+"?",21,INK));
        Spinner method=select(l,"Как фактически оплатили",new String[]{"Наличные","СБП","Терминал / другое приложение"},"cash".equals(order.method)?0:"sbp".equals(order.method)?1:2);
        l.addView(label("Для СБП проверьте поступление в банковском приложении. Для терминала — успешную операцию на терминале. Скриншот покупателя сам по себе не подтверждает оплату.",15,MUTED));
        show(new AlertDialog.Builder(this).setTitle("Проверка продавцом").setView(l).setNegativeButton("Назад",null).setPositiveButton("Деньги получены",(d,w)->attempt(()->{db.confirmPaid(order.id,new String[]{"cash","sbp","terminal"}[method.getSelectedItemPosition()]);orderScreen(order.id);})));}
    private void openPaymentApp(RetailDb.Order order){db.requireSeller();String pkg=db.setting("payment_package","");Intent launch=getPackageManager().getLaunchIntentForPackage(pkg);if(launch==null)throw new IllegalStateException("Приложение не установлено. Выберите его заново в настройках оплаты.");startActivity(launch);}
    private void refreshBank(long id,int version,android.webkit.WebView web,TextView status){if(version!=screenVersion||isFinishing()||isDestroyed())return;if(bankBusy){timer.postDelayed(()->refreshBank(id,version,web,status),1000);return;}bankBusy=true;
        bankWorker.execute(()->{SberGateway.Result result=null;String error=null;try{result=gateway.refresh(id);}catch(Exception e){error=e instanceof javax.net.ssl.SSLException?"Не удалось проверить сертификат банка. Проверьте дату устройства и доверенные сертификаты; проверка защиты не отключается.":e instanceof org.json.JSONException?"Банк вернул неожиданный ответ. Не оплачивайте повторно; обратитесь к продавцу.":e instanceof java.io.IOException?"Нет связи с банком. Не оплачивайте повторно; касса повторит проверку.":e.getMessage();}final SberGateway.Result answer=result;final String failure=error;runOnUiThread(()->{bankBusy=false;if(version!=screenVersion||isFinishing()||isDestroyed())return;if(answer!=null&&(answer.paid||answer.cancelled)){orderScreen(id);return;}status.setText(failure==null?answer.message:failure);if(answer!=null&&answer.url!=null&&web.getUrl()==null)web.loadUrl(answer.url);timer.postDelayed(()->{if(screenOrder==id&&version==screenVersion)refreshBank(id,version,web,status);},5000);});});
    }
    private void queue(boolean history,long cursor){db.requireSeller();page(history?"Все заказы":"Оплаченные · к сборке");
        queueScreen=true;queueHistory=history;queueCursor=cursor;
        button(body,"Новая продажа",()->{db.clearCurrentOrder();catalog();},true);
        button(history?"К сборке":"Все заказы / ручная оплата",()->queue(!history,0));
        List<RetailDb.Order> orders=db.orders(history,cursor);if(orders.isEmpty())note(history?"Здесь пока нет заказов.":"Пока нет оплаченных заказов к сборке.");
        for(RetailDb.Order o:orders)button("№"+o.id+" · "+Domain.money(o.total)+"\n"+status(o),()->orderScreen(o.id));
        if(orders.size()==100)button("Следующие 100",()->queue(history,orders.get(orders.size()-1).id));
        if(cursor>0)button("К началу списка",()->queue(history,0));
        button("Обновить список",()->queue(history,cursor));
    }
    private void admin(){db.requireAdmin();page("Настройки");
        button("Товары и цены",this::products);
        button("Точка и оплата",this::payments);
        button("Сценарий покупателя",this::behavior);
        button("Автоматическая оплата · Сбер СБП",this::bankSettings);
        button("Продажи и популярные позиции",()->statistics(0));
        button("Резервная копия",this::backups);
        button("Изменить PIN-коды",this::pins);
        button("Открыть продажи",()->queue(false,0));
        button(body,"Перейти к клиенту",this::lock,true);
        note("После 3 минут без действий доступ сотрудника закроется. После минуты в другом приложении потребуется повторный вход.");
    }
    private void products(){db.requireAdmin();page("Товары и цены");button(body,"Добавить товар",()->productEditor(null),true);
        note("Ненужные товары можно скрыть. Ранее оформленные заказы сохраняют свои названия и цены.");
        for(Domain.Product p:db.products(true))button(p.name+" · "+Domain.money(p.price)+("kg".equals(p.unit)?" / кг":" / шт.")+(p.active?"":" · скрыт"),()->productEditor(p));
    }
    private void productEditor(Domain.Product p){db.requireAdmin();page(p==null?"Новый товар":"Изменить товар");EditText name=field("Название",p==null?"":p.name,InputType.TYPE_CLASS_TEXT);
        Spinner unit=select(body,"Единица продажи",new String[]{"Штуки","Килограммы"},p!=null&&"kg".equals(p.unit)?1:0);
        EditText price=field("Цена за штуку / килограмм, ₽",p==null?"":java.math.BigDecimal.valueOf(p.price,2).toPlainString(),InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        CheckBox active=check("Показывать в каталоге",p==null||p.active);
        button(body,"Сохранить товар",()->{db.saveProduct(p==null?0:p.id,name.getText().toString(),unit.getSelectedItemPosition()==0?"piece":"kg",Domain.parsePrice(price.getText().toString()),active.isChecked());products();},true);
        button("Назад",this::products);
    }
    private void payments(){db.requireAdmin();page("Точка и оплата");EditText name=field("Название точки",db.setting("name","Малая касса"),InputType.TYPE_CLASS_TEXT);
        CheckBox cash=check("Наличные",db.enabled("cash")),sbp=check("СБП · банковский QR или ссылка",db.enabled("sbp")),terminal=check("Отдельный терминал / приложение",db.enabled("terminal"));
        note("Получите QR для приёма платежей у вашего банка. Это должен быть QR торговой точки; приложение не создаёт банковские реквизиты.");
        String stored=db.setting("sbp_image","");note(stored.isEmpty()?"QR-код ещё не добавлен.":"QR-код добавлен.");
        button("Загрузить изображение QR-кода",()->pick("image/*",QR));
        if(!stored.isEmpty())button("Удалить QR-код",()->{if(db.enabled("sbp")&&db.setting("sbp_link","").isEmpty())throw new IllegalStateException("Сначала отключите СБП или добавьте ссылку и сохраните настройки.");db.saveQr("");payments();});
        EditText link=field("Ссылка оплаты от банка · необязательно",db.setting("sbp_link",""),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        note("Статический QR не передаёт сумму автоматически. Клиент вводит её в банковском приложении, продавец проверяет поступление.");
        text("Платёжное приложение: "+db.setting("payment_package","не выбрано"));button("Выбрать установленное приложение",this::choosePaymentApp);
        note("Кнопка откроет выбранное приложение. Сумму вводит продавец; результат не считывается автоматически. Для самостоятельной оплаты подключите Сбер СБП в отдельном разделе настроек.");
        button(body,"Сохранить настройки",()->{db.saveSettings(name.getText().toString(),cash.isChecked(),sbp.isChecked(),terminal.isChecked(),link.getText().toString(),db.setting("payment_package",""));admin();},true);
        button("Назад",this::admin);
    }
    private void choosePaymentApp(){db.requireAdmin();Intent query=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);List<ResolveInfo> apps=getPackageManager().queryIntentActivities(query,0);apps.removeIf(r->r.activityInfo.packageName.equals(getPackageName()));apps.sort((a,b)->a.loadLabel(getPackageManager()).toString().compareToIgnoreCase(b.loadLabel(getPackageManager()).toString()));
        String[] names=new String[apps.size()+1];names[0]="Не использовать приложение";for(int i=0;i<apps.size();i++)names[i+1]=apps.get(i).loadLabel(getPackageManager())+"\n"+apps.get(i).activityInfo.packageName;
        show(new AlertDialog.Builder(this).setTitle("Платёжное приложение").setItems(names,(d,index)->attempt(()->{db.savePaymentPackage(index==0?"":apps.get(index-1).activityInfo.packageName);payments();})).setNegativeButton("Назад",null));
    }
    private void behavior(){db.requireAdmin();page("Сценарий покупателя");Spinner mode=select(body,"Как покупатель получает товары",new String[]{"Клиент оплачивает → продавец собирает","Клиент берёт товары → оплачивает"},db.enabled("self_serve")?1:0);CheckBox weight=check("Клиент сам вводит вес с весов / этикетки",db.enabled("self_weigh"));note("Для весовых товаров нужны отдельные весы. Приложение не получает вес автоматически. Для полностью самостоятельной оплаты включите СБП с подтверждением банка; наличные и отдельный терминал требуют продавца.");button(body,"Сохранить сценарий",()->{db.saveBehavior(mode.getSelectedItemPosition()==1,weight.isChecked());admin();},true);}
    private void bankSettings(){db.requireAdmin();page("Автоматическая оплата · Сбер");note("Нужен договор интернет-эквайринга Сбера с СБП и API-доступ к шлюзу ecommerce. Данные выдаёт банк; обычный пароль СберБизнес сюда не подходит.");CheckBox enabled=check("Включить автоматическую оплату СБП",db.enabled("bank_auto")),test=check("Тестовый шлюз · без настоящих денег",db.enabled("bank_test"));EditText user=field("API-логин банка","",InputType.TYPE_CLASS_TEXT),password=field("API-пароль банка","",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD),returnUrl=field("HTTPS-ссылка возврата, согласованная с банком",db.setting("bank_return",""),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);note("Учётные данные шифруются на устройстве, в резервную копию не входят. При включении заново введите логин и пароль. После сохранения СБП включится автоматически. При отключении банка без запасного QR касса вернётся к ручным способам оплаты.");button(body,"Сохранить подключение",()->{gateway.configure(enabled.isChecked(),test.isChecked(),user.getText().toString(),password.getText().toString(),returnUrl.getText().toString());admin();},true);button("Открыть инструкцию Сбера",()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://ecomtest.sberbank.ru/doc"))));button("Сбросить попытки, для которых QR не получен",()->show(new AlertDialog.Builder(this).setTitle("Отменить попытки без QR?").setMessage("Это отменит только заказы, для которых касса ещё не получила банковский платёж и QR. Заказы с уже полученной банковской ссылкой останутся для проверки.").setNegativeButton("Назад",null).setPositiveButton("Сбросить",(d,w)->attempt(()->{int count=db.discardUnregisteredBank();Toast.makeText(this,"Отменено попыток: "+count,Toast.LENGTH_LONG).show();bankSettings();}))));note("Если ошиблись в подключении и QR ещё не был получен, сбросьте такие попытки перед исправлением настроек. Для уже созданного платежа можно обновить пароль того же банковского аккаунта.");}
    private void statistics(int period)throws Exception{db.requireAdmin();page("Продажи");LinearLayout filters=new LinearLayout(this);body.addView(filters);button(filters,"Сегодня",()->statistics(0),false);button(filters,"7 дней",()->statistics(1),false);button(filters,"Всё время",()->statistics(2),false);
        Calendar c=Calendar.getInstance();c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);if(period==1)c.add(Calendar.DAY_OF_MONTH,-6);
        JSONObject stats=db.stats(period==2?0:c.getTimeInMillis());long sales=stats.getLong("sales"),revenue=stats.getLong("revenue");title(period==0?"Сегодня":period==1?"Последние 7 дней":"Всё время");title(Domain.money(revenue));text("Продаж: "+sales);text("Средняя покупка: "+Domain.money(sales==0?0:(revenue+sales/2)/sales));
        note("Учитываются только заказы с подтверждённой оплатой. Период определяется временем получения оплаты.");title("Популярные позиции");JSONArray top=stats.getJSONArray("top");if(top.length()==0)note("Данных пока нет.");for(int i=0;i<top.length();i++){JSONObject p=top.getJSONObject(i);text((i+1)+". "+p.getString("name"));note("В заказах: "+p.getLong("orders")+" · продано "+Domain.quantity(p.getLong("quantity"),p.getString("unit"))+" · "+Domain.money(p.getLong("revenue")));}
    }
    private void pins(){db.requireAdmin();page("PIN-коды");Spinner role=select(body,"Чей PIN изменить",new String[]{"Продавца","Администратора"},0);EditText current=pin(body,"Текущий PIN администратора"),next=pin(body,"Новый PIN · 6–12 цифр"),repeat=pin(body,"Повторите новый PIN");
        button(body,"Изменить PIN",()->{if(!next.getText().toString().equals(repeat.getText().toString()))throw new IllegalArgumentException("Новые PIN-коды не совпадают.");db.changePin(role.getSelectedItemPosition()==0?Domain.Role.SELLER:Domain.Role.ADMIN,current.getText().toString(),next.getText().toString());if(db.role()==Domain.Role.CLIENT)catalog();else admin();},true);
        note("PIN-коды не включаются в резервную копию. Сохраните PIN администратора отдельно.");
    }
    private void backups(){db.requireAdmin();page("Резервная копия");note("Сохраняются товары, настройки оплаты и история заказов. PIN-коды не копируются. Храните файл в безопасном месте.");
        button("Сохранить копию в файл",()->{db.requireAdmin();exportText=db.exportBackup().toString();Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"small-pos-"+new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(new Date())+".json");startActivityForResult(i,EXPORT);});
        button("Восстановить из файла",()->pick("*/*",IMPORT));note("Восстановление заменит текущие товары и заказы. Доступы на этом устройстве сохранятся. Перед заменой сделайте копию текущих данных.");
    }
    private void pick(String type,int code){db.requireAdmin();startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type),code);}
    private byte[] read(Uri uri,int max)throws Exception{try(InputStream input=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){if(input==null)throw new IOException("Не удалось открыть файл.");byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){if(out.size()+n>max)throw new IllegalArgumentException("Файл слишком большой.");out.write(buffer,0,n);}return out.toByteArray();}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null){exportText=null;return;}
        attempt(()->{db.requireAdmin();Uri uri=data.getData();if(request==EXPORT){if(exportText==null)throw new IllegalStateException("Повторите сохранение копии.");try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("Не удалось создать файл.");out.write(exportText.getBytes(java.nio.charset.StandardCharsets.UTF_8));}exportText=null;Toast.makeText(this,"Резервная копия сохранена",Toast.LENGTH_LONG).show();backups();}
            else if(request==QR){byte[] bytes=read(uri,8*1024*1024);BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(options.outWidth<1||options.outHeight<1||options.outWidth>20000||options.outHeight>20000)throw new IllegalArgumentException("Выберите обычное изображение QR-кода.");options.inJustDecodeBounds=false;options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>1024)options.inSampleSize*=2;Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(bitmap==null)throw new IllegalArgumentException("Изображение не читается.");ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,out);bitmap.recycle();db.saveQr(Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP));payments();}
            else if(request==IMPORT){JSONObject copy=new JSONObject(new String(read(uri,32*1024*1024),java.nio.charset.StandardCharsets.UTF_8));show(new AlertDialog.Builder(this).setTitle("Заменить данные кассы?").setMessage("Текущие товары, настройки оплаты и история будут заменены данными из файла. Ваши PIN-коды останутся прежними.").setNegativeButton("Назад",null).setPositiveButton("Восстановить",(d,w)->attempt(()->{db.restoreBackup(copy);cart.clear();admin();Toast.makeText(this,"Данные восстановлены",Toast.LENGTH_LONG).show();})));}
        });
    }
}
