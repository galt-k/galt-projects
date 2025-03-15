use crate::include::common::config::PageId;
use std::fs::{File, OpenOptions};
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::sync::{Arc, Mutex}; 
use std::path::Path;


impl DiskManager {
    fn new(db_file: &str) -> io::Result<Self> {
        let file = OpenOptions::new().read(true).append(true).create(true).open(db_file)?;
        Ok(Self {
            db_file: db_file.to_string(),
            file: Arc::new(Mutex::new(file)), // wrapping in Arc mutex- clone works
        })
    }
    fn read_page(&mut self, page_id: PageId, data: &mut [u8]) -> io::Result<()> {
        //lock thread safe
        let mut file = self.file.lock().unwrap();
        file.seek(SeekFrom::Starrt(0))?;
        let mut buffer = Vec::new();
        file.read_to_end(&mut buffer);

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
            None => Err(io::Error::new(io::ErrorKind::NotFound,"Page not found")),
        }

    }


    fn write_page(&self, page_id: PageId, data: &mut [u8]) {
        let mut file = self.file.lock().unwrap(); // Lock—thread-safe—unwrap ok for Task 2
        let mut buffer = Vec::new();
        buffer.extend_from_slice(&page_id.to_le_bytes()); // page_id—4 bytes
        buffer.extend_from_slice(data); // Data—assume 4096 bytes—Task 2 page size
        file.write_all(&buffer)?; // Append—your design
        file.flush()?; // Ensure written—C++ flushes—Task 2 reliability
        Ok(())
    }
}