package test.practice;

import java.util.*;
import java.util.stream.*;
import static java.util.Comparator.*;
import static java.util.stream.Collectors.*;

record Person(String name, String dept, int age, double salary){}

public class Streams {
    static List<Person> people = List.of(
        new Person("Alice", "Math", 30, 120000),
        new Person("Bob", "HR", 35, 100000),
        new Person("Ben", "Math", 55, 90000),
        new Person("Alex", "Science", 25, 800000),
        new Person("Henry", "Philosophy", 45, 100000)
    );

    public static void main(String[] args) {
        System.out.println("Streams and collectors");

        //1. Group by department and count 
        // Use COllect when i need to materialize the stream into a concrete collection.
        // Want to map grouped by something?
        var byDeptCount = people
        .stream()
        .collect(groupingBy(Person::dept, counting()));
        System.out.println("1. Count per Dept: "+ byDeptCount + "\n");

        //2. Group by Dept list of names
        var namesByDept = people
                .stream()
                .collect(groupingBy(Person::dept, mapping(Person::name, toList())));

        System.out.println("2. Names by Dept: "+ namesByDept + "\n");
        
        //3. Total Salary by Dept
        var salByDept = people
                .stream()
                .collect(groupingBy(Person::dept, summingDouble(Person::salary)));
        System.out.println("3. Salary by Dept: "+ salByDept + "\n");

        //4. Max by Dept
        Map<String, Double> maxSalaryByDept = people.stream()
            .collect(toMap(
                Person::dept,           // key
                Person::salary,         // value         → salary
                Double::max             // merge function → if two people in same dept, keep the higher salary
            ));
        System.out.println("4. Max Salary by Dept: "+ maxSalaryByDept + "\n");
    }
    
}
