package ru.smallpos.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The only authority for access, prices, orders and sales. No cloud or HTTP service. */
public final class RetailDb extends SQLiteOpenHelper {
    private Domain.Role role=Domain.Role.CLIENT;
    public static final class Order {
        public long id, total, created, paidAt;
        public String state, method, storeName;
        public boolean done, selfServe;
        public List<Domain.Line> lines=new ArrayList<>();
    }
    public RetailDb(Context context) {this(context,"small-pos.sqlite");}
    public RetailDb(Context context,String name) {super(context,name,null,1);setWriteAheadLoggingEnabled(true);}
    @Override public void onConfigure(SQLiteDatabase db) {db.setForeignKeyConstraintsEnabled(true);}
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
        db.execSQL("CREATE TABLE users(role TEXT PRIMARY KEY,salt TEXT NOT NULL,hash TEXT NOT NULL,failures INTEGER NOT NULL DEFAULT 0,locked_until INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE products(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,unit TEXT NOT NULL CHECK(unit IN ('piece','kg')),price INTEGER NOT NULL CHECK(price>0),active INTEGER NOT NULL CHECK(active IN (0,1)))");
        db.execSQL("CREATE TABLE orders(id INTEGER PRIMARY KEY AUTOINCREMENT,request_key TEXT NOT NULL UNIQUE,fingerprint TEXT NOT NULL,total INTEGER NOT NULL,state TEXT NOT NULL CHECK(state IN ('pending','paid','cancelled')),method TEXT NOT NULL,store_name TEXT NOT NULL,created INTEGER NOT NULL,paid_at INTEGER NOT NULL DEFAULT 0,paid_by TEXT,done INTEGER NOT NULL DEFAULT 0,self_serve INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE bank_payments(order_id INTEGER PRIMARY KEY REFERENCES orders(id) ON DELETE CASCADE,number TEXT NOT NULL UNIQUE,bank_id TEXT UNIQUE,form_url TEXT NOT NULL DEFAULT '',test INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE order_lines(order_id INTEGER NOT NULL REFERENCES orders(id) ON DELETE CASCADE,product_id INTEGER NOT NULL,name TEXT NOT NULL,unit TEXT NOT NULL,price INTEGER NOT NULL,quantity INTEGER NOT NULL,amount INTEGER NOT NULL,PRIMARY KEY(order_id,product_id))");
        db.execSQL("CREATE INDEX idx_orders_state_id ON orders(state,id)");
        db.execSQL("CREATE INDEX idx_orders_paid_at ON orders(paid_at)");
        put(db,"name","Малая касса");put(db,"revision","1");put(db,"cash","true");put(db,"sbp","false");put(db,"terminal","false");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {throw new IllegalStateException("Эта версия базы пока не поддерживается.");}
    private static void put(SQLiteDatabase db,String key,String value) {ContentValues values=new ContentValues();values.put("key",key);values.put("value",value);db.insertWithOnConflict("settings",null,values,SQLiteDatabase.CONFLICT_REPLACE);}
    public synchronized String setting(String key,String fallback) {try(Cursor cursor=getReadableDatabase().rawQuery("SELECT value FROM settings WHERE key=?",new String[]{key})){return cursor.moveToFirst()?cursor.getString(0):fallback;}}
    public synchronized boolean enabled(String key) {return "true".equals(setting(key,"false"));}
    public synchronized long revision() {return Long.parseLong(setting("revision","1"));}
    private void bump(SQLiteDatabase db) {put(db,"revision",Long.toString(revision()+1));}
    public synchronized Domain.Role role() {return role;}
    public synchronized void logout() {role=Domain.Role.CLIENT;}
    public synchronized void requireAdmin() {if(role!=Domain.Role.ADMIN)throw new SecurityException("Нужен доступ администратора.");}
    public synchronized void requireSeller() {if(role==Domain.Role.CLIENT)throw new SecurityException("Нужен доступ продавца.");}
    public synchronized boolean configured() {try(Cursor cursor=getReadableDatabase().rawQuery("SELECT 1 FROM users WHERE role='admin'",null)){return cursor.moveToFirst();}}
    public synchronized void setup(String name,String adminPin,String sellerPin,String template) {
        name=validName(name);Domain.validatePin(adminPin);Domain.validatePin(sellerPin);
        if(adminPin.equals(sellerPin))throw new IllegalArgumentException("Для администратора и продавца нужны разные PIN-коды.");
        String adminSalt=Domain.salt(),sellerSalt=Domain.salt();
        String adminHash=Domain.pinHash(adminPin,adminSalt),sellerHash=Domain.pinHash(sellerPin,sellerSalt);
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {if(configured())throw new IllegalStateException("Касса уже настроена.");
            insertUser(db,"admin",adminSalt,adminHash);insertUser(db,"seller",sellerSalt,sellerHash);put(db,"name",name);
            seed(db,template);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        role=Domain.Role.ADMIN;
    }
    private static void insertUser(SQLiteDatabase db,String role,String salt,String hash){ContentValues value=new ContentValues();value.put("role",role);value.put("salt",salt);value.put("hash",hash);db.insertOrThrow("users",null,value);}
    private static String validName(String name){if(name==null||name.trim().isEmpty()||name.trim().length()>80)throw new IllegalArgumentException("Укажите название, не длиннее 80 символов.");return name.trim();}
    public synchronized boolean login(Domain.Role target,String pin) {
        if(target==Domain.Role.CLIENT){logout();return true;}
        String key=target==Domain.Role.ADMIN?"admin":"seller";
        try(Cursor cursor=getReadableDatabase().rawQuery("SELECT salt,hash,failures,locked_until FROM users WHERE role=?",new String[]{key})){
            if(!cursor.moveToFirst())throw new IllegalStateException("Сначала настройте кассу.");
            long locked=cursor.getLong(3),now=System.currentTimeMillis();
            if(locked>now)throw new IllegalStateException("Слишком много попыток. Повторите через 5 минут.");
            boolean valid=pin!=null&&pin.matches("[0-9]{6,12}")&&Domain.sameHash(Domain.pinHash(pin,cursor.getString(0)),cursor.getString(1));
            int failures=valid?0:(locked>0&&locked<=now?0:cursor.getInt(2))+1;
            ContentValues value=new ContentValues();value.put("failures",failures);value.put("locked_until",failures>=5?now+300000:0);
            getWritableDatabase().update("users",value,"role=?",new String[]{key});
            if(valid)role=target;
            return valid;
        }
    }
    public synchronized void changePin(Domain.Role target,String currentAdminPin,String nextPin) {
        requireAdmin();if(target==Domain.Role.CLIENT)throw new IllegalArgumentException("У клиента нет PIN-кода.");
        Domain.validatePin(nextPin);
        if(!verifyPin("admin",currentAdminPin))throw new SecurityException("Текущий PIN администратора неверен.");
        String key=target==Domain.Role.ADMIN?"admin":"seller",other=target==Domain.Role.ADMIN?"seller":"admin";
        if(verifyPin(other,nextPin))throw new IllegalArgumentException("PIN-коды администратора и продавца должны различаться.");
        String salt=Domain.salt();ContentValues values=new ContentValues();values.put("salt",salt);values.put("hash",Domain.pinHash(nextPin,salt));values.put("failures",0);values.put("locked_until",0);
        getWritableDatabase().update("users",values,"role=?",new String[]{key});
        if(target==Domain.Role.ADMIN)logout();
    }
    private boolean verifyPin(String key,String pin){try(Cursor cursor=getReadableDatabase().rawQuery("SELECT salt,hash FROM users WHERE role=?",new String[]{key})){return cursor.moveToFirst()&&pin!=null&&pin.matches("[0-9]{6,12}")&&Domain.sameHash(Domain.pinHash(pin,cursor.getString(0)),cursor.getString(1));}}
    public synchronized List<Domain.Product> products(boolean includeInactive) {
        List<Domain.Product> result=new ArrayList<>();
        try(Cursor cursor=getReadableDatabase().rawQuery("SELECT id,name,unit,price,active FROM products"+(includeInactive?"":" WHERE active=1")+" ORDER BY id",null)){
            while(cursor.moveToNext())result.add(new Domain.Product(cursor.getLong(0),cursor.getString(1),cursor.getString(2),cursor.getLong(3),cursor.getInt(4)==1));
        }return result;
    }
    public synchronized void saveProduct(long id,String name,String unit,long price,boolean active) {
        requireAdmin();Domain.Product product=new Domain.Product(id>0?id:1,name,unit,price,active);
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {ContentValues values=new ContentValues();values.put("name",product.name);values.put("unit",unit);values.put("price",price);values.put("active",active?1:0);
            if(id>0){if(db.update("products",values,"id=?",new String[]{Long.toString(id)})!=1)throw new IllegalArgumentException("Товар не найден.");}
            else {if(products(true).size()>=100)throw new IllegalArgumentException("В каталоге не больше 100 позиций.");db.insertOrThrow("products",null,values);}
            bump(db);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public synchronized void saveSettings(String name,boolean cash,boolean sbp,boolean terminal,String link,String packageName) {
        requireAdmin();name=validName(name);
        if(!cash&&!sbp&&!terminal)throw new IllegalArgumentException("Включите хотя бы один способ оплаты.");
        if(link==null)link="";link=link.trim();
        if(!link.isEmpty()){
            android.net.Uri uri=android.net.Uri.parse(link);
            if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||link.length()>2048)throw new IllegalArgumentException("Используйте HTTPS-ссылку оплаты, выданную банком.");
        }
        if(sbp&&!enabled("bank_auto")&&setting("sbp_image","").isEmpty()&&link.isEmpty())throw new IllegalArgumentException("Для СБП подключите банк или добавьте банковский QR-код / ссылку оплаты.");
        if(packageName==null)packageName="";
        if(!packageName.isEmpty()&&!packageName.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))throw new IllegalArgumentException("Проверьте платёжное приложение.");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{put(db,"name",name);put(db,"cash",Boolean.toString(cash));put(db,"sbp",Boolean.toString(sbp));put(db,"terminal",Boolean.toString(terminal));put(db,"sbp_link",link);put(db,"payment_package",packageName);bump(db);db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    public synchronized void saveQr(String base64){requireAdmin();validateQr(base64);SQLiteDatabase d=getWritableDatabase();d.beginTransaction();try{put(d,"sbp_image",base64);bump(d);d.setTransactionSuccessful();}finally{d.endTransaction();}}
    public synchronized void savePaymentPackage(String name){requireAdmin();if(!name.isEmpty()&&!name.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))throw new IllegalArgumentException("Проверьте платёжное приложение.");SQLiteDatabase d=getWritableDatabase();d.beginTransaction();try{put(d,"payment_package",name);bump(d);d.setTransactionSuccessful();}finally{d.endTransaction();}}
    private static void validateQr(String code){if(code==null||code.length()>1500000)throw new IllegalArgumentException("QR-код слишком большой.");if(!code.isEmpty()){byte[] bytes;try{bytes=android.util.Base64.decode(code,android.util.Base64.DEFAULT);}catch(Exception e){throw new IllegalArgumentException("QR-код в копии повреждён.");}android.graphics.BitmapFactory.Options o=new android.graphics.BitmapFactory.Options();o.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);if(o.outWidth<1||o.outHeight<1||o.outWidth>2048||o.outHeight>2048)throw new IllegalArgumentException("Изображение QR-кода повреждено или слишком большое.");}}
    public synchronized Map<Long,Long> draft() {
        Map<Long,Long> result=new LinkedHashMap<>();
        try{JSONObject data=new JSONObject(setting("draft","{}"));java.util.Iterator<String> keys=data.keys();while(keys.hasNext()){String key=keys.next();long id=Long.parseLong(key),quantity=data.getLong(key);if(id>0&&quantity>0&&quantity<=100000)result.put(id,quantity);}}catch(Exception ignored){}
        return result;
    }
    public synchronized void saveDraft(Map<Long,Long> cart){JSONObject data=new JSONObject();try{for(Map.Entry<Long,Long> item:cart.entrySet())data.put(item.getKey().toString(),item.getValue());}catch(JSONException error){throw new IllegalArgumentException("Не удалось сохранить корзину.");}put(getWritableDatabase(),"draft",data.toString());}
    public synchronized long currentOrder(){return Long.parseLong(setting("current_order","0"));}
    public synchronized void clearCurrentOrder(){put(getWritableDatabase(),"current_order","0");}
    public synchronized List<Domain.Line> quote(Map<Long,Long> cart) {
        List<Domain.Line> result=new ArrayList<>();
        Map<Long,Domain.Product> catalog=new LinkedHashMap<>();for(Domain.Product product:products(true))catalog.put(product.id,product);
        for(Map.Entry<Long,Long> entry:cart.entrySet()){
            Domain.Product product=catalog.get(entry.getKey());
            if(product==null||!product.active)throw new IllegalArgumentException("Товар больше не доступен. Обновите корзину.");
            if(role==Domain.Role.CLIENT&&"kg".equals(product.unit)&&!enabled("self_weigh"))throw new SecurityException("Весовые товары оформляет продавец после взвешивания.");
            result.add(new Domain.Line(product,entry.getValue()));
        }return result;
    }
    public synchronized Order createOrder(Map<Long,Long> cart,long expectedRevision,long expectedTotal,String requestKey,String method) {
        if(!configured())throw new IllegalStateException("Сначала настройте кассу.");
        if(requestKey==null||!requestKey.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Некорректный номер запроса.");
        String fingerprint=Domain.fingerprint(cart,expectedRevision,expectedTotal,method);
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            try(Cursor previous=db.rawQuery("SELECT id,fingerprint FROM orders WHERE request_key=?",new String[]{requestKey})){
                if(previous.moveToFirst()){
                    if(!fingerprint.equals(previous.getString(1)))throw new IllegalArgumentException("Повторный запрос содержит другой заказ.");
                    Order result=order(previous.getLong(0));db.setTransactionSuccessful();return result;
                }
            }
            if(revision()!=expectedRevision)throw new IllegalArgumentException("Товары или цены изменились. Проверьте заказ ещё раз.");
            if(!enabled(method)||!("cash".equals(method)||"sbp".equals(method)||"terminal".equals(method)))throw new IllegalArgumentException("Выбранный способ оплаты недоступен.");
            List<Domain.Line> lines=quote(cart);long total=Domain.total(lines);
            if(total!=expectedTotal)throw new IllegalArgumentException("Сумма изменилась. Проверьте заказ ещё раз.");
            ContentValues values=new ContentValues();values.put("request_key",requestKey);values.put("fingerprint",fingerprint);values.put("total",total);values.put("state","pending");values.put("method",method);values.put("store_name",setting("name","Малая касса"));values.put("created",System.currentTimeMillis());
            values.put("self_serve",enabled("self_serve")?1:0);
            long id=db.insertOrThrow("orders",null,values);
            for(Domain.Line line:lines){ContentValues row=new ContentValues();row.put("order_id",id);row.put("product_id",line.product.id);row.put("name",line.product.name);row.put("unit",line.product.unit);row.put("price",line.product.price);row.put("quantity",line.quantity);row.put("amount",line.amount);db.insertOrThrow("order_lines",null,row);}
            put(db,"draft","{}");put(db,"current_order",Long.toString(id));
            db.setTransactionSuccessful();return order(id);
        }finally{db.endTransaction();}
    }
    public synchronized Order order(long id){
        Order result=new Order();
        try(Cursor cursor=getReadableDatabase().rawQuery("SELECT id,total,state,method,store_name,created,paid_at,done,self_serve FROM orders WHERE id=?",new String[]{Long.toString(id)})){
            if(!cursor.moveToFirst())throw new IllegalArgumentException("Заказ не найден.");
            result.id=cursor.getLong(0);result.total=cursor.getLong(1);result.state=cursor.getString(2);result.method=cursor.getString(3);result.storeName=cursor.getString(4);result.created=cursor.getLong(5);result.paidAt=cursor.getLong(6);result.done=cursor.getInt(7)==1;
            result.selfServe=cursor.getInt(8)==1;
        }
        try(Cursor cursor=getReadableDatabase().rawQuery("SELECT product_id,name,unit,price,quantity,amount FROM order_lines WHERE order_id=? ORDER BY product_id",new String[]{Long.toString(id)})){
            while(cursor.moveToNext()){Domain.Line line=new Domain.Line(new Domain.Product(cursor.getLong(0),cursor.getString(1),cursor.getString(2),cursor.getLong(3),true),cursor.getLong(4));if(line.amount!=cursor.getLong(5))throw new IllegalStateException("Сумма позиции повреждена.");result.lines.add(line);}
        }if(Domain.total(result.lines)!=result.total)throw new IllegalStateException("Сумма заказа повреждена.");return result;
    }
    public synchronized List<Order> orders(boolean history,long cursorId){requireSeller();List<Order> result=new ArrayList<>();String where=history?" WHERE (?=0 OR id<?)":" WHERE state='paid' AND done=0 AND id>?";
        String[] args=history?new String[]{Long.toString(cursorId),Long.toString(cursorId)}:new String[]{Long.toString(cursorId)};
        try(Cursor cursor=getReadableDatabase().rawQuery("SELECT id FROM orders"+where+" ORDER BY id "+(history?"DESC":"ASC")+" LIMIT 100",args)){while(cursor.moveToNext())result.add(order(cursor.getLong(0)));}return result;}
    public synchronized Order confirmPaid(long id,String method){requireSeller();if(!("cash".equals(method)||"sbp".equals(method)||"terminal".equals(method)))throw new IllegalArgumentException("Неизвестный способ оплаты.");
        if(hasBankPayment(id))throw new IllegalStateException("Этот платёж проверяется через банк. Обновите его статус; ручное подтверждение не применяется.");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{Order order=order(id);
            if("paid".equals(order.state)){if(!method.equals(order.method))throw new IllegalStateException("Продажа уже подтверждена другим способом.");db.setTransactionSuccessful();return order;}
            if(!"pending".equals(order.state))throw new IllegalStateException("Заказ отменён.");
            ContentValues values=new ContentValues();values.put("state","paid");values.put("method",method);values.put("paid_at",System.currentTimeMillis());values.put("paid_by",role.name());values.put("done",order.selfServe?1:0);db.update("orders",values,"id=? AND state='pending'",new String[]{Long.toString(id)});db.setTransactionSuccessful();return order(id);
        }finally{db.endTransaction();}}
    public synchronized void cancel(long id){requireSeller();Order order=order(id);if(hasBankPayment(id))throw new IllegalStateException("Сначала урегулируйте платёж в банке. Автоматические платежи нельзя отменять локально.");if("paid".equals(order.state))throw new IllegalStateException("Подтверждённую продажу нельзя отменить этим действием.");ContentValues values=new ContentValues();values.put("state","cancelled");getWritableDatabase().update("orders",values,"id=? AND state='pending'",new String[]{Long.toString(id)});}
    public synchronized boolean hasBankPayment(long id){try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM bank_payments WHERE order_id=?",new String[]{Long.toString(id)})){return c.moveToFirst();}}
    public synchronized boolean bankPending(){try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM bank_payments b JOIN orders o ON b.order_id=o.id WHERE o.state='pending' LIMIT 1",null)){return c.moveToFirst();}}
    synchronized List<Long> pendingBankIds(long after){List<Long> ids=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT o.id FROM bank_payments b JOIN orders o ON b.order_id=o.id WHERE o.state='pending' AND o.id>? ORDER BY o.id LIMIT 5",new String[]{Long.toString(after)})){while(c.moveToNext())ids.add(c.getLong(0));}return ids;}
    public synchronized int discardUnregisteredBank(){requireAdmin();SQLiteDatabase d=getWritableDatabase();d.beginTransaction();int count=0;try{List<Long> ids=new ArrayList<>();try(Cursor c=d.rawQuery("SELECT o.id FROM bank_payments b JOIN orders o ON b.order_id=o.id WHERE o.state='pending' AND b.bank_id IS NULL",null)){while(c.moveToNext())ids.add(c.getLong(0));}for(long id:ids){String[] arg={Long.toString(id)};ContentValues v=new ContentValues();v.put("state","cancelled");d.update("orders",v,"id=? AND state='pending'",arg);d.delete("bank_payments","order_id=? AND bank_id IS NULL",arg);count++;}d.setTransactionSuccessful();return count;}finally{d.endTransaction();}}
    public synchronized void saveBankConfig(boolean enabled,boolean test,String url){requireAdmin();if(enabled("bank_auto")&&bankPending())throw new IllegalStateException("Сначала проверьте незавершённые банковские платежи. Подключение нельзя менять во время оплаты.");if(enabled)SberGateway.validHttps(url);SQLiteDatabase d=getWritableDatabase();d.beginTransaction();try{put(d,"bank_auto",Boolean.toString(enabled));if(enabled)put(d,"sbp","true");else if(setting("sbp_image","").isEmpty()&&setting("sbp_link","").isEmpty()){put(d,"sbp","false");if(!enabled("cash")&&!enabled("terminal"))put(d,"cash","true");}put(d,"bank_test",Boolean.toString(test));put(d,"bank_return",url);bump(d);d.setTransactionSuccessful();}finally{d.endTransaction();}}
    public synchronized void saveBehavior(boolean selfServe,boolean selfWeigh){requireAdmin();SQLiteDatabase d=getWritableDatabase();d.beginTransaction();try{put(d,"self_serve",Boolean.toString(selfServe));put(d,"self_weigh",Boolean.toString(selfWeigh));bump(d);d.setTransactionSuccessful();}finally{d.endTransaction();}}
    synchronized String bankNumber(long id){Order o=order(id);if(!"pending".equals(o.state)||!"sbp".equals(o.method))throw new IllegalStateException("Заказ не ожидает оплаты СБП.");SQLiteDatabase d=getWritableDatabase();d.execSQL("INSERT OR IGNORE INTO bank_payments(order_id,number,test) SELECT id,request_key,? FROM orders WHERE id=?",new Object[]{enabled("bank_test")?1:0,id});try(Cursor c=d.rawQuery("SELECT number FROM bank_payments WHERE order_id=?",new String[]{Long.toString(id)})){c.moveToFirst();return c.getString(0);}}
    synchronized String bankId(long id){return bankField(id,"bank_id");}
    synchronized String bankUrl(long id){return bankField(id,"form_url");}
    synchronized boolean bankTest(long id){return "1".equals(bankField(id,"test"));}
    private String bankField(long id,String field){try(Cursor c=getReadableDatabase().rawQuery("SELECT "+field+" FROM bank_payments WHERE order_id=?",new String[]{Long.toString(id)})){return c.moveToFirst()&&!c.isNull(0)?c.getString(0):"";}}
    synchronized void attachBank(long id,String bankId,String url,String number){if(!bankId.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Банк вернул неверный идентификатор.");SberGateway.validBankUrl(url);if(!number.equals(bankField(id,"number")))throw new IllegalStateException("Заказ изменился.");ContentValues v=new ContentValues();v.put("bank_id",bankId);v.put("form_url",url);getWritableDatabase().update("bank_payments",v,"order_id=? AND bank_id IS NULL",new String[]{Long.toString(id)});}
    synchronized void applyBankPayment(long id,SberGateway.VerifiedPayment p){SQLiteDatabase d=getWritableDatabase();d.beginTransaction();try{Order o=order(id);if(p==null||p.amount!=o.total||!p.bankId.equals(bankId(id))||!p.number.equals(bankField(id,"number")))throw new SecurityException("Подтверждение банка не соответствует заказу.");if("paid".equals(o.state)||"cancelled".equals(o.state)){d.setTransactionSuccessful();return;}if(!"pending".equals(o.state))throw new IllegalStateException("Заказ уже закрыт.");ContentValues v=new ContentValues();v.put("state",p.paid?"paid":"cancelled");v.put("paid_at",p.paid?System.currentTimeMillis():0);v.put("paid_by",bankTest(id)?"BANK_TEST":"BANK");v.put("done",p.paid&&o.selfServe?1:0);d.update("orders",v,"id=? AND state='pending'",new String[]{Long.toString(id)});d.setTransactionSuccessful();}finally{d.endTransaction();}}
    public synchronized void complete(long id){requireSeller();Order order=order(id);if(!"paid".equals(order.state))throw new IllegalStateException("Сначала подтвердите получение оплаты.");ContentValues values=new ContentValues();values.put("done",1);getWritableDatabase().update("orders",values,"id=? AND state='paid'",new String[]{Long.toString(id)});}
    public synchronized JSONObject stats(long since)throws JSONException{requireAdmin();JSONObject result=new JSONObject();
        try(Cursor cursor=getReadableDatabase().rawQuery("SELECT COUNT(*),COALESCE(SUM(total),0) FROM orders WHERE state='paid' AND COALESCE(paid_by,'')!='BANK_TEST' AND paid_at>=?",new String[]{Long.toString(since)})){cursor.moveToFirst();result.put("sales",cursor.getLong(0));result.put("revenue",cursor.getLong(1));}
        JSONArray top=new JSONArray();try(Cursor cursor=getReadableDatabase().rawQuery("SELECT l.product_id,l.name,l.unit,COUNT(DISTINCT o.id),SUM(l.quantity),SUM(l.amount) FROM order_lines l JOIN orders o ON o.id=l.order_id WHERE o.state='paid' AND COALESCE(o.paid_by,'')!='BANK_TEST' AND o.paid_at>=? GROUP BY l.product_id,l.name,l.unit ORDER BY COUNT(DISTINCT o.id) DESC,SUM(l.amount) DESC LIMIT 5",new String[]{Long.toString(since)})){while(cursor.moveToNext()){JSONObject item=new JSONObject();item.put("id",cursor.getLong(0));item.put("name",cursor.getString(1));item.put("unit",cursor.getString(2));item.put("orders",cursor.getLong(3));item.put("quantity",cursor.getLong(4));item.put("revenue",cursor.getLong(5));top.put(item);}}
        result.put("top",top);return result;
    }
    private static void seed(SQLiteDatabase db,String template){String[][] values;
        if("coffee".equals(template))values=new String[][]{{"Эспрессо","140"},{"Американо","180"},{"Капучино","230"},{"Латте","250"},{"Флэт уайт","260"},{"Чай","150"},{"Какао","220"},{"Матча-латте","270"},{"Круассан","170"},{"Маффин","190"}};
        else if("produce".equals(template))values=new String[][]{{"Помидоры","180"},{"Огурцы","140"},{"Картофель","60"},{"Морковь","70"},{"Лук","65"},{"Капуста","55"},{"Перец","240"},{"Свёкла","75"},{"Яблоки","150"},{"Груши","200"}};
        else if("food".equals(template))values=new String[][]{{"Чебурек с мясом","160"},{"Чебурек с сыром","150"},{"Чебурек с картофелем","130"},{"Пирожок","70"},{"Самса","120"},{"Хот-дог","170"},{"Чай","80"},{"Кофе","120"},{"Вода 0,5 л","70"},{"Морс 0,3 л","90"}};
        else return;
        for(String[] item:values){ContentValues value=new ContentValues();value.put("name",item[0]);value.put("price",Domain.parsePrice(item[1]));value.put("unit","produce".equals(template)?"kg":"piece");value.put("active",1);db.insertOrThrow("products",null,value);}
    }
    public synchronized JSONObject exportBackup()throws JSONException{requireAdmin();JSONObject root=new JSONObject();root.put("format","small-pos");root.put("version",1);root.put("exported",System.currentTimeMillis());
        JSONObject settings=new JSONObject();for(String key:new String[]{"name","cash","sbp","terminal","sbp_link","sbp_image","payment_package","self_serve","self_weigh"})settings.put(key,setting(key,""));root.put("settings",settings);
        JSONArray products=new JSONArray();for(Domain.Product p:products(true)){JSONObject item=new JSONObject();item.put("id",p.id);item.put("name",p.name);item.put("unit",p.unit);item.put("price",p.price);item.put("active",p.active);products.put(item);}root.put("products",products);
        JSONArray orders=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,request_key,fingerprint,paid_by FROM orders ORDER BY id",null)){while(c.moveToNext()){Order order=order(c.getLong(0));JSONObject item=new JSONObject();item.put("id",order.id);item.put("requestKey",c.getString(1));item.put("fingerprint",c.getString(2));item.put("paidBy",c.getString(3));item.put("total",order.total);item.put("state",order.state);item.put("method",order.method);item.put("name",order.storeName);item.put("created",order.created);item.put("paidAt",order.paidAt);item.put("done",order.done);item.put("selfServe",order.selfServe);JSONArray lines=new JSONArray();for(Domain.Line line:order.lines){JSONObject row=new JSONObject();row.put("id",line.product.id);row.put("name",line.product.name);row.put("unit",line.product.unit);row.put("price",line.product.price);row.put("quantity",line.quantity);lines.put(row);}item.put("lines",lines);orders.put(item);}}root.put("orders",orders);
        JSONArray banks=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT order_id,number,bank_id,form_url,test FROM bank_payments",null)){while(c.moveToNext()){JSONObject b=new JSONObject();b.put("order",c.getLong(0));b.put("number",c.getString(1));b.put("id",c.isNull(2)?"":c.getString(2));b.put("url",c.getString(3));b.put("test",c.getInt(4)==1);banks.put(b);}}root.put("banks",banks);return root;
    }
    public synchronized void restoreBackup(JSONObject root)throws JSONException{
        requireAdmin();if(bankPending())throw new IllegalStateException("Перед восстановлением проверьте незавершённые платежи в банке.");if(!"small-pos".equals(root.optString("format"))||root.optInt("version")!=1)throw new IllegalArgumentException("Это не резервная копия Малой кассы.");
        JSONObject settings=root.getJSONObject("settings");String name=validName(settings.getString("name"));JSONArray products=root.getJSONArray("products"),orders=root.getJSONArray("orders");
        for(String key:new String[]{"cash","sbp","terminal"})if(!settings.getString(key).matches("true|false"))throw new IllegalArgumentException("В копии неверные настройки оплаты.");
        if(!"true".equals(settings.getString("cash"))&&!"true".equals(settings.getString("sbp"))&&!"true".equals(settings.getString("terminal")))throw new IllegalArgumentException("В копии не включена оплата.");
        String link=settings.optString("sbp_link",""),pkg=settings.optString("payment_package",""),qr=settings.optString("sbp_image","");
        if(!link.isEmpty()){android.net.Uri u=android.net.Uri.parse(link);if(!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||link.length()>2048)throw new IllegalArgumentException("В копии неверная ссылка оплаты.");}
        if(!pkg.isEmpty()&&!pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))throw new IllegalArgumentException("В копии неверное платёжное приложение.");validateQr(qr);
        for(String key:new String[]{"self_serve","self_weigh"})if(!settings.optString(key,"false").matches("true|false|"))throw new IllegalArgumentException("В копии неверный сценарий покупателя.");
        if(products.length()>100)throw new IllegalArgumentException("Копия слишком большая.");
        java.util.Set<Long> productIds=new java.util.HashSet<>(),orderIds=new java.util.HashSet<>();
        for(int i=0;i<products.length();i++){JSONObject p=products.getJSONObject(i);Domain.Product v=new Domain.Product(p.getLong("id"),p.getString("name"),p.getString("unit"),p.getLong("price"),p.getBoolean("active"));if(!productIds.add(v.id))throw new IllegalArgumentException("В копии повторяются товары.");}
        for(int i=0;i<orders.length();i++){JSONObject o=orders.getJSONObject(i);long id=o.getLong("id");if(id<1||!orderIds.add(id))throw new IllegalArgumentException("В копии повторяются заказы.");if(!o.getString("requestKey").matches("[a-f0-9-]{36}")||!o.getString("fingerprint").matches("[a-f0-9]{64}"))throw new IllegalArgumentException("В копии неверные идентификаторы заказов.");validName(o.getString("name"));String state=o.getString("state"),method=o.getString("method");if(!state.matches("pending|paid|cancelled")||!method.matches("cash|sbp|terminal"))throw new IllegalArgumentException("Некорректный статус заказа.");if(o.getLong("created")<1||("paid".equals(state)&&o.getLong("paidAt")<1))throw new IllegalArgumentException("Некорректная дата заказа.");JSONArray rows=o.getJSONArray("lines");List<Domain.Line> lines=new ArrayList<>();java.util.Set<Long> ids=new java.util.HashSet<>();for(int j=0;j<rows.length();j++){JSONObject l=rows.getJSONObject(j);Domain.Product p=new Domain.Product(l.getLong("id"),l.getString("name"),l.getString("unit"),l.getLong("price"),true);if(!ids.add(p.id))throw new IllegalArgumentException("В заказе повторяются товары.");lines.add(new Domain.Line(p,l.getLong("quantity")));}if(Domain.total(lines)!=o.getLong("total"))throw new IllegalArgumentException("Суммы в копии не сходятся.");}
        JSONArray banks=root.optJSONArray("banks");if(banks==null)banks=new JSONArray();
        java.util.Set<String> numbers=new java.util.HashSet<>(),bankIds=new java.util.HashSet<>();java.util.Set<Long> bankOrders=new java.util.HashSet<>();
        for(int i=0;i<banks.length();i++){JSONObject b=banks.getJSONObject(i);long id=b.getLong("order");String number=b.getString("number"),bid=b.getString("id"),url=b.getString("url");if(!orderIds.contains(id)||!bankOrders.add(id)||!numbers.add(number)||!number.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("В копии неверные банковские заказы.");if(!bid.isEmpty()){if(!bid.matches("[a-f0-9-]{36}")||!bankIds.add(bid))throw new IllegalArgumentException("В копии неверные номера платежей.");SberGateway.validBankUrl(url);}else if(!url.isEmpty())throw new IllegalArgumentException("В копии платёжная ссылка без номера платежа.");b.getBoolean("test");boolean match=false;for(int j=0;j<orders.length();j++){JSONObject o=orders.getJSONObject(j);if(o.getLong("id")==id){match=number.equals(o.getString("requestKey"))&&"sbp".equals(o.getString("method"));break;}}if(!match)throw new IllegalArgumentException("Банковский платёж не совпадает с заказом.");}
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            db.delete("order_lines",null,null);db.delete("orders",null,null);db.delete("products",null,null);
            for(int i=0;i<products.length();i++){JSONObject p=products.getJSONObject(i);ContentValues v=new ContentValues();v.put("id",p.getLong("id"));v.put("name",p.getString("name"));v.put("unit",p.getString("unit"));v.put("price",p.getLong("price"));v.put("active",p.getBoolean("active")?1:0);db.insertOrThrow("products",null,v);}
            for(int i=0;i<orders.length();i++){JSONObject o=orders.getJSONObject(i);ContentValues v=new ContentValues();long id=o.getLong("id");v.put("id",id);v.put("request_key",o.getString("requestKey"));v.put("fingerprint",o.getString("fingerprint"));v.put("total",o.getLong("total"));v.put("state",o.getString("state"));v.put("method",o.getString("method"));v.put("store_name",validName(o.getString("name")));v.put("created",o.getLong("created"));v.put("paid_at",o.getLong("paidAt"));v.put("paid_by",o.optString("paidBy",""));v.put("done",o.getBoolean("done")?1:0);v.put("self_serve",o.optBoolean("selfServe",false)?1:0);db.insertOrThrow("orders",null,v);JSONArray rows=o.getJSONArray("lines");for(int j=0;j<rows.length();j++){JSONObject l=rows.getJSONObject(j);ContentValues row=new ContentValues();row.put("order_id",id);row.put("product_id",l.getLong("id"));row.put("name",l.getString("name"));row.put("unit",l.getString("unit"));row.put("price",l.getLong("price"));row.put("quantity",l.getLong("quantity"));row.put("amount",Domain.amount(l.getLong("price"),l.getLong("quantity"),l.getString("unit")));db.insertOrThrow("order_lines",null,row);}}
            for(int i=0;i<banks.length();i++){JSONObject b=banks.getJSONObject(i);ContentValues v=new ContentValues();v.put("order_id",b.getLong("order"));v.put("number",b.getString("number"));if(!b.getString("id").isEmpty())v.put("bank_id",b.getString("id"));v.put("form_url",b.getString("url"));v.put("test",b.getBoolean("test")?1:0);db.insertOrThrow("bank_payments",null,v);}
            put(db,"bank_auto","false");put(db,"name",name);for(String key:new String[]{"cash","sbp","terminal","sbp_link","sbp_image","payment_package"})put(db,key,settings.optString(key,""));for(String key:new String[]{"self_serve","self_weigh"})put(db,key,"true".equals(settings.optString(key))?"true":"false");put(db,"draft","{}");put(db,"current_order","0");bump(db);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
}
