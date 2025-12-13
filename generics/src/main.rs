use std::fmt;
// Car factory 
// Car Iterator
// create a 

// Create a generic Car that takes in a Engine of certain types
// Here you dont need a generic type. You can just directly put
// Engine in the car type
// How do you Model Engines here? There are 3 different classifications of engine types?
// Each Engine has multiple types if Engine Instances? 
// 1. classify every thing as enums?
pub trait GetMetadata {
    fn get_name(&self) -> String;
    fn get_id(&self) -> i32;
}

// generic build Trait- Engine can be anytype E
pub trait Build<E> {
    fn build(&mut self, engine: E, id: usize);
}

pub trait HasName {
    fn name(&self) -> &str;
}

pub trait HasId {
    fn id(&self) -> &i32;
}

#[derive(Debug, Clone)]
pub enum EngineType {
    FUEL(FUEL),
    POWER(POWER),
    CYLINDER_LAYOUT(CYLINDER_LAYOUT),
    IGNITION(IGNITION), 
}

impl fmt::Display for EngineType {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            EngineType::FUEL(_) => write!(f, "Gasoline engine"),
            EngineType::POWER(_) => write!(f, "Electric motor"),
            EngineType::CYLINDER_LAYOUT(_)   => write!(f, "CYLINDER_LAYOUT"),
            EngineType::IGNITION(_) => write!(f, "IGNITION"),
        }
    }
}

impl GetMetadata for EngineType {
    fn get_name(&self) -> String {
        match self {
            EngineType::FUEL(_) => "Gasoline engine".to_string(),
            EngineType::POWER(_) => "Electric motor".to_string(),
            EngineType::CYLINDER_LAYOUT(_)   => "CYLINDER_LAYOUT".to_string(),
            EngineType::IGNITION(_) => "IGNITION".to_string(),
        }
    }

    fn get_id(&self) -> i32 {
        match self {
            EngineType::FUEL(_) => 1,
            EngineType::POWER(_) => 2,
            EngineType::CYLINDER_LAYOUT(_)   => 3,
            EngineType::IGNITION(_) => 4,
        }
    }
}

impl<T> GetMetadata for T 
    where 
        T: HasName + HasId
{
        fn get_name(&self) -> String {
            // delegate the 
            self.name().to_string()
        }

        fn get_id(&self) -> i32 {
            *self.id()
        }

}



#[derive(Debug,Clone)]
pub enum FUEL{
    PETROL,
    DIESEL,
}

#[derive(Debug,Clone)]
pub enum POWER {
    ELECTRIC,
    HYBRID,
}

#[derive(Debug,Clone)]
pub enum CYLINDER_LAYOUT {
    ELECTRIC,
    HYBRID,
}

#[derive(Debug,Clone)]
pub enum IGNITION {
    ELECTRIC,
    HYBRID,
}

// I wanted to type to implement Display trait
// accept the types that implment the display behavior
#[derive(Debug, Clone)]
pub struct Car<T>
    where T: GetMetadata {
    id: i32,
    engine: Option<T>,
    name: String
}

// Car has a name → implements HasName
impl<T> HasName for Car<T>
where
    T: GetMetadata,
{
    fn name(&self) -> &str {
        &self.name
    }
}

impl<T> HasId for Car<T>
where
    T: GetMetadata,
{
    fn id(&self) -> &i32 {
        &self.id
    }
}

impl<T> Build<T> for Car<T>
where
    T: GetMetadata + Clone,
{
    fn build(&mut self, engine: T, id: usize) {
        self.id = id as i32;
        self.engine = Some(engine);
        
    }
}


// impl<T> Iterator for T
// where 
//     T: GetMetadata
// {
//     fn next(&mut self) -> Option<Self::Item {
//         let current = self.curr;
//         // Add 1 to id
//         // Clone the object
//         // use the same name and return it. 
//     }
// }


// I will just print the metadat of the object. I dont care the type here
// Anyone who implements the GetMetadata trait.
fn show_metadata<T>(t: &T) 
    where T: GetMetadata {
    // call the metadat behavior
    println!("Name - {}, id - {}", t.get_name(), t.get_id());

}

// A car factory which returns an iterator, which generates the cars
// provide an engine type as a parammeter, the mthod returns an iterator of 10 vehicles
// Not restricted to Cars. provide any engine, provide any vehicel type an iterator factory line will be created 
// Which can be used to supply the vechicles 
fn create_cars<E, T>(engine: &E, prototype: &T)-> impl Iterator<Item = T>
    where E: Clone + GetMetadata,
          T: Clone + Build<E> + GetMetadata
{
    // Clone the Engine 10 times
    // clone the Vehicle 10 times
    (0..10).map(move |i| {
        let mut vehicle = prototype.clone();
        let new_engine = engine.clone(); 
        vehicle.build(new_engine, i);
        vehicle
    })
}


fn main() {

    let car: Car<EngineType> = Car { id: 1, engine: Some(EngineType::FUEL(FUEL::PETROL)), name: "Ford Mustang".to_string()};
    // Print the metdata
    show_metadata(&car);
    show_metadata(&car.engine.unwrap());

    // Create an Iterator of vehicles
    // Create an iterator of 10 cars
    let prototype = Car {
        id: 0,
        engine: None,
        name: "Nissan Rogue".to_string(),
    };

    let electric_engine = EngineType::POWER(POWER::ELECTRIC);

    let fleet: Vec<_> = create_cars(&electric_engine, &prototype).collect();

    println!("\nFactory output (10 cars):");
    for vehicle in fleet {
        show_metadata(&vehicle);
    }
}