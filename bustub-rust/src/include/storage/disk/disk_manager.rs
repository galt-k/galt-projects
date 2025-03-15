use std::fs::{File, OpenOptions};
use std::sync::{Arc, Mutex};

#[derive(Clone)]
pub struct DiskManager {
    db_file: String,        // file path eg- "test.db"
    file: Arc<Mutex<File>>, // Open file handle
}
