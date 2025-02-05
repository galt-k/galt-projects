// src/main.rs
use metalog::*;
use std::error::Error;

fn main() -> Result<(), Box<dyn Error>> {
    let catalog = Catalog::new();

    // Create a table
    let table = Table {
        name: "users".to_string(),
        columns: vec![
            Column {
                name: "id".to_string(),
                data_type: "integer".to_string(),
                is_nullable: false,
            },
            Column {
                name: "name".to_string(),
                data_type: "string".to_string(),
                is_nullable: true,
            },
        ],
    };

    catalog.create_table(table)?;
    println!("Created table 'users'");

    // Save to disk
    catalog.save_to_disk("catalog.json")?;
    println!("Saved catalog to disk");

    // Load from disk
    let loaded_catalog = Catalog::load_from_disk("catalog.json")?;
    let loaded_table = loaded_catalog.get_table("users").unwrap();
    println!("Loaded table: {:?}", loaded_table);

    Ok(())
}