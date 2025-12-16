use std::fmt;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::Mutex;
use tokio::task;
use tokio::time::sleep;
use std::fmt::Debug;  // ← This line is missing!
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

// A lighweight view into a car
// Borrows data
pub struct CarView<'a> {
    pub id: i32,
    pub name: &'a str,
    pub engine_name: &'a str,
}

//Factory that returns borrowed views
fn create_car_views<'a, T>(
    cars: &'a [Car<T>],
) -> impl Iterator<Item = CarView<'a>> + 'a
where 
    T: GetMetadata,
{
    cars.iter().map(|car| {
    // Borrow the name directly
        let name = &car.name;

        // Borrow the engine name — but get_name() returns String, so we can't borrow it directly
        // Instead, we use a static fallback for "No engine"
        let engine_name = car.engine
            .as_ref()
            .and_then(|e| {
                // We can't return &str from e.get_name().as_str() because the String is temporary
                // So we use a static string instead
                Some(match e.get_id() {
                    1 => "Gasoline (Petrol) Engine",
                    2 => "Pure Electric Motor",
                    3 => "Custom Cylinder Layout",
                    4 => "Custom Ignition",
                    _ => "Unknown Engine",
                })
        })
        .unwrap_or("No engine");

        CarView {
            id: car.id,
            name: name,
            engine_name,
        }
    })
}

// Adding concurrenyc safe types
// Car should be concurrent safe
// There will be 3 operations performed paralley on Car object
// 1. Add engine
// 2. Add Tyres
// 3. Add seats

#[derive(Debug)]
pub struct ThreadSafeCar<T>
where
T: Send + Sync + GetMetadata + Clone + Debug,  // ← Add Debug here!
{
    pub id: i32,
    pub name: String,
    pub engine: Mutex<Option<T>>,
    pub tyres: Mutex<u8>,
    pub seats: Mutex<u8>,
}


// Async assembly operations
async fn install_engine<T>(car: Arc<ThreadSafeCar<T>>, engine: T)
where
    T: Send + Sync + GetMetadata + Clone + Debug,
{
    println!("[Engine Task] Installing {}...", engine.get_name());
    sleep(Duration::from_millis(800)).await;

    let mut engine_guard = car.engine.lock().await;
    *engine_guard = Some(engine);

    println!("[Engine Task] Engine installed!");
}

async fn install_tyres<T>(car: Arc<ThreadSafeCar<T>>)
where
    T: Send + Sync + GetMetadata + Clone + Debug,
{
    println!("[Tyres Task] Installing 4 tyres...");
    sleep(Duration::from_millis(600)).await;

    let mut tyres_guard = car.tyres.lock().await;
    *tyres_guard = 4;

    println!("[Tyres Task] Tyres installed!");
}

async fn install_seats<T>(car: Arc<ThreadSafeCar<T>>)
where
    T: Send + Sync + GetMetadata + Clone + Debug,
{
    println!("[Seats Task] Installing 5 seats...");
    sleep(Duration::from_millis(700)).await;

    let mut seats_guard = car.seats.lock().await;
    *seats_guard = 5;

    println!("[Seats Task] Seats installed!");
}

// Parallel assembly
async fn assemble_car<T>(engine: T) -> ThreadSafeCar<T>
where
    T: Send + Sync + GetMetadata + Clone + 'static + Debug,
{
    let car = Arc::new(ThreadSafeCar {
        id: 1,
        name: "Ford Mustang".to_string(),
        engine: Mutex::new(None),
        tyres: Mutex::new(0),
        seats: Mutex::new(0),
    });

    let car1 = Arc::clone(&car);
    let car2 = Arc::clone(&car);
    let car3 = Arc::clone(&car);

    let engine_task = task::spawn(async move {
        install_engine(car1, engine).await;
    });

    let tyres_task = task::spawn(async move {
        install_tyres(car2).await;
    });

    let seats_task = task::spawn(async move {
        install_seats(car3).await;
    });

    let _ = tokio::try_join!(engine_task, tyres_task, seats_task);

    Arc::try_unwrap(car).unwrap()
}


#[tokio::main]
async fn main() {

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
    for vehicle in &fleet {
        show_metadata(vehicle);
    }

    // Borrowed views — zero allocation!
    let views = create_car_views( &fleet);

    for view in views {
        println!("Car ID: {}, Name: {}, Engine: {}", view.id, view.name, view.engine_name);
    }

    let electric_engine = EngineType::POWER(POWER::ELECTRIC);

    let assembled = assemble_car(electric_engine).await;

    println!("Final car: {:?}", assembled);

}