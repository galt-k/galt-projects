use crate::include::common::config::PageId;
//use crate::include::storage::disk::disk_manager::DiskManager;
use std::fs::{File, OpenOptions};
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::sync::{Arc, Mutex}; 
use std::path::Path;

#[derive(Clone)]
pub struct DiskManager {
    pub db_file: String,        // file path eg- "test.db"
    pub file: Arc<Mutex<File>>, // Open file handle
}


impl DiskManager {
    pub fn new(db_file: &str) -> io::Result<Self> {
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .open(db_file)?;
        Ok(Self {
            db_file: db_file.to_string(),
            file: Arc::new(Mutex::new(file)),
        })
    }

    pub fn read_page(&self, page_id: PageId, data: &mut [u8]) -> io::Result<()> {
        let mut file = self.file.lock().unwrap();
        file.seek(SeekFrom::Start(0))?;
        let mut buffer = Vec::new();
        file.read_to_end(&mut buffer)?;

        let entry_size = 4 + 4096;
        let mut last_match = None;
        for i in (0..buffer.len()).step_by(entry_size) {
            if i + entry_size > buffer.len() { break; }
            let pid = i32::from_le_bytes(buffer[i..i+4].try_into().unwrap());
            if pid == page_id {
                last_match = Some(i);
            }            
        }
        match last_match {
            Some(pos) => {
                data.copy_from_slice(&buffer[pos + 4..pos + 4 + 4096]);
                Ok(())
            }
            None => Err(io::Error::new(io::ErrorKind::NotFound, "Page not found")),
        }
    }

    pub fn write_page(&self, page_id: PageId, data: &[u8]) -> io::Result<()> {
        let mut file = self.file.lock().unwrap();
        let mut buffer = Vec::new();
        buffer.extend_from_slice(&page_id.to_le_bytes());
        buffer.extend_from_slice(data);
        file.write_all(&buffer)?;
        file.flush()?;
        Ok(())
    }
}