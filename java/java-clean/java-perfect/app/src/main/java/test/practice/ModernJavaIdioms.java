package test.practice;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;

public class ModernJavaIdioms {
    // 1. var + diamond
    // Never write the type twice after new
    static void var(){
        var names = new ArrayList<String>();
        var map = new HashMap<String, Integer>();
        System.out.println("1. var + diamond" + "\n");
        System.out.println(names);
    } 

    // 2. record
    // Immutable fields
    static  record Person(String name, int age) {
        // Compact constructor for validation (runs automatically)
        Person {
            if (age < 0 || age > 150) throw new IllegalArgumentException("Invalid Age");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Name required");
        }
            // Custom method- you can add behavior
            boolean isAdult() {return age>= 18;}
            String greeting() {return "Hi i'm "+ name;}
    }

    static void records() {
        System.out.println("2. Immutable data classes python dataclasess + RUST struct");
        
        var alice = new Person("Alice", 30);
        var charlie = new Person("Charlie", 120);

        System.out.println(alice);
        System.out.println(charlie);
        System.out.println(charlie.isAdult());
        System.out.println(charlie.greeting() + "\n");
    }

    //3. immutable collections
    static void immutableCollections() {
        System.out.println("3. Immutable Collections");
        var primes = List.of(2 ,3 ,5, 7, 11);
        var vowels = Set.of("a", "e", "i", "o", "u");
        var roman = Map.of(1, "I", 5,"V", 10, "X");

        System.out.println("Primes: " + primes + "\n");
        System.out.println("Roman numerals: " + roman + "\n");
        System.out.println("Roman numerals: " + vowels);
        System.out.println("\n");
    }

    //4. Switch expressions
    static String switchExpressions(DayOfWeek day) {
        return switch (day) {
            case MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY -> "Work hard";
            case SATURDAY, SUNDAY -> "Sleep in";
        };
    }
    
    static void switchDemo() {
        System.out.println("Switch Expressions");
        var today = LocalDate.now().getDayOfWeek();
        System.out.println( "Today is " + today + " -> " + switchExpressions(today) + "\n");
    }
    
    public static void main(String[] args) {
        System.out.println("Modern JAVA");
        var();
        records();
        immutableCollections();
        switchDemo();
    }


    

    
}
