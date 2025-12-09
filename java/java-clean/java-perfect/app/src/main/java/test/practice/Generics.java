package test.practice;
import java.util.*;
import java.util.function.*;

public class Generics {
    // 1. Basic Generic Class + Diamond operator
    static class Box<T> {
        private T value;
        public void set(T value) { this.value = value;}
        public T get() {return value;}
        @Override public String toString() {return "Box["+ value + "]";}
    }

    static void basicGenerics() {
        System.out.println("1. Basic generics + Diamond");

        var stringBox = new Box<String>(); //diamond infers the type
        stringBox.set("Hello genrics");
        System.out.println(" "+stringBox.get());

        var intBox = new Box<Integer>();
        intBox.set(42);
        System.out.println(" "+ intBox);
        System.out.println();
    }

    // 2. Bounded Type parameters
    static <T extends Number & Comparable<T>> T max(T a, T b) {
        return a.compareTo(b) > 0 ? a : b;
    }

    static void boundedTypes() {
        System.out.println("2. Bounded Types");
        System.out.println("  max(10,20) = " + max(10, 20));
        System.out.println();
    }

    //3. Generic Methods + Type infernce
    static <T> List<T> reverse(List<T> list) {
        var reversed = new ArrayList<T>(list);
        Collections.reverse(reversed);
        return reversed;
    }

    static void genericMethods() {
        System.out.println("3. Genric methods + Infernce");

        var numbers = List.of(1, 2, 3, 4, 5);
        System.out.println(" Original: " + numbers);
        System.out.println(" Reversed: " + reverse(numbers));
        System.out.println();
    }

    //4. Wildcards - PECS( producer Estends, Consumer Super)
    static void printNumbers(List<? extends Number> list) {
        System.out.println("  Numbers: ");
        for (Number n : list) System.out.println(n + " ");
        System.out.println();
    } 

    static void addIntegers(List<? super Integer> list) {
        list.add(100);
        list.add(200);
        System.out.println("  Added 100 and 200 to the super list");
    }

    static void wildcards() {
        System.out.println("4. Wildcards -PECS");
        List<Integer> ints = new ArrayList<>(List.of(1,2,3));
        List<Double> doubles = List.of(1.1, 3.3);
        
        printNumbers(ints);
        printNumbers(doubles);

        List<Number> numbers = new ArrayList<>();
        List<Object> objects = new ArrayList<>();

        addIntegers(objects);
        addIntegers(numbers);

        System.out.println("  " + numbers);
        System.out.println();
    }

    //5. Generic Records
    record Pair<T, U>(T first, U second) {}

    static void genericRecords() {
        System.out.println("5. Genric Records");

        var pair = new Pair<>("Age", 30);
        System.out.println("  " + pair);
        System.out.println("  First: "+ pair.first() + " Second: "+ pair.second());
        System.out.println();
    }

    // Generic Stack
    static class Stack<E> {
        private final Deque<E> deque = new ArrayDeque<>();

        public void push(E element) { deque.push(element); }
        public E pop() { return deque.pop(); }
        public boolean isEmpty() { return deque.isEmpty(); }
        public int size() { return deque.size(); }
    }

    static void genericStack() {
        System.out.println("7. Generic Stack Implementation");

        var stack = new Stack<String>();
        stack.push("first");
        stack.push("second");
        System.out.println("   Pop: " + stack.pop());
        System.out.println("   Pop: " + stack.pop());
        System.out.println();
    }

    public static void main(String[] args) {
        System.out.println("JAVA GENERICS MASTERY 2025\n" + "=".repeat(60));

        basicGenerics();
        boundedTypes();
        genericMethods();
        wildcards();
        genericRecords();
        genericStack();

        System.out.println("You now master Java Generics!");
    }

}
