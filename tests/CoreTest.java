package ru.smallpos.app;
import java.util.*;

public final class CoreTest {
    static int checks;
    static void equal(Object a,Object b){checks++;if(!a.equals(b))throw new AssertionError(a+" != "+b);}
    static void rejected(Runnable action){checks++;try{action.run();}catch(IllegalArgumentException|SecurityException e){return;}throw new AssertionError("Expected rejection");}
    public static void main(String[] args){
        equal(Domain.parsePrice("180,50"),18050L);equal(Domain.parsePrice("0.01"),1L);
        rejected(()->Domain.parsePrice("0"));rejected(()->Domain.parsePrice("-1"));rejected(()->Domain.parsePrice("1.234"));rejected(()->Domain.parsePrice("1e3"));
        equal(Domain.parseQuantity("0,650","kg"),650L);equal(Domain.parseQuantity("100","kg"),100000L);
        rejected(()->Domain.parseQuantity("100.001","kg"));rejected(()->Domain.parseQuantity("1.5","piece"));rejected(()->Domain.parseQuantity("0","piece"));
        equal(Domain.amount(18000,650,"kg"),11700L);equal(Domain.amount(150,10,"kg"),2L);equal(Domain.amount(19999,3,"piece"),59997L);
        rejected(()->Domain.amount(1,1,"kg"));rejected(()->Domain.amount(99999999,999,"piece"));
        List<Domain.Line> lines=Arrays.asList(new Domain.Line(new Domain.Product(1,"Чай","piece",1001,true),3),new Domain.Line(new Domain.Product(2,"Яблоки","kg",12345,true),650));
        equal(Domain.total(lines),11027L);rejected(()->Domain.total(Collections.emptyList()));
        Map<Long,Long>a=new LinkedHashMap<>(),b=new LinkedHashMap<>();a.put(2L,1L);a.put(1L,2L);b.put(1L,2L);b.put(2L,1L);
        equal(Domain.fingerprint(a,1,100,"cash"),Domain.fingerprint(b,1,100,"cash"));
        equal(Domain.fingerprint(a,1,100,"cash").equals(Domain.fingerprint(a,1,100,"sbp")),false);
        String salt=Domain.salt(),hash=Domain.pinHash("123456",salt);
        equal(Domain.sameHash(hash,Domain.pinHash("123456",salt)),true);equal(Domain.sameHash(hash,Domain.pinHash("654321",salt)),false);
        rejected(()->Domain.validatePin("12345"));rejected(()->Domain.validatePin("abcdef"));
        equal(Domain.bankOutcome("one",100,"one",100,"643",2,"DEPOSITED",100,0),1);
        for(int state:new int[]{0,1,4,5})equal(Domain.bankOutcome("one",100,"one",100,"643",state,"",0,0),0);
        for(int state:new int[]{3,6})equal(Domain.bankOutcome("one",100,"one",100,"643",state,"",0,0),-1);
        rejected(()->Domain.bankOutcome("one",100,"two",100,"643",2,"DEPOSITED",100,0));
        rejected(()->Domain.bankOutcome("one",100,"one",99,"643",2,"DEPOSITED",100,0));
        rejected(()->Domain.bankOutcome("one",100,"one",100,"840",2,"DEPOSITED",100,0));
        rejected(()->Domain.bankOutcome("one",100,"one",100,"643",2,"APPROVED",100,0));
        rejected(()->Domain.bankOutcome("one",100,"one",100,"643",2,"DEPOSITED",99,0));
        rejected(()->Domain.bankOutcome("one",100,"one",100,"643",2,"DEPOSITED",100,1));
        System.out.println("PASS: "+checks+" core checks");
    }
}
