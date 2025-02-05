use serde::{Serialize, Deserialize};
use dashmap::DashMap;
use std::sync::Arc;
use std::fs::File;
use std::io::{Read, Write};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Column {
    pub name: String,
    pub data_type: String,
    pub is_nullable: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Table{
    pub name: String,
    pub columns: Vec<Column>, 
}

// Many other metdata can be added for the above structs like indexes, constraints, statistics etc..

#[derive(Debug)]
pub struct Catalog {
    pub tables: DashMap<String, Table>, //Dashmap provides concurrent access
}

impl Catalog {
    pub fn new() -> Self {
        Catalog {
            tables: DashMap::new(), 
        }
    } 

    // Basic operations to create table, get table, delete table
    pub fn create_table(&self, table: Table) ->  Result<(), String> {
        if self.tables.contains_key(&table.name) {
            return Err(format!("Table '{}' already exists", table.name));
        }
        self.tables.insert(table.name.clone(), table);
        Ok(())
    }

    pub fn get_table(&self, name: &str) -> Option<Table> {
        self.tables.get(name).map(|entry| entry.value().clone())
    }

    pub fn save_to_disk(&self, path: &str) -> Result<(), String> {
        let tables: Vec<Table> = self.tables.iter().map(|entry| entry.value().clone()).collect();
        let json = serde_json::to_string(&tables).map_err(|e| e.to_string())?;
        let mut file = File::create(path).map_err(|e| e.to_string())?;
        file.write_all(json.as_bytes()).map_err(|e| e.to_string())?;
        Ok(())
    }

    pub fn load_from_disk(path: &str) -> Result<Self, String> {
        let mut file = File::open(path).map_err(|e| e.to_string())?;
        let mut json = String::new();
        file.read_to_string(&mut json).map_err(|e| e.to_string())?;
        let tables: Vec<Table> = serde_json::from_str(&json).map_err(|e| e.to_string())?;
        let catalog = Catalog::new();
        for table in tables {
            catalog.create_table(table)?;
        }
        Ok(catalog)
    }
}