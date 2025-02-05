use std::fs::{File, OpenOptions};
use std::io::{self, Read, Write, Seek, SeekFrom};
use std::path::Path; 
use thiserror::Error; 

#[derive(Debug, Error)]
pub enum IoError {
    #[error("File not found: {0}")]
    FileNotFound(String),
    #[error("I/O error: {0}")]
    Io(#[from] io::Error),
    #[error("Block out of bounds")]
    BlockOutOfBounds,
}

pub struct IoService {
    file: File,
    block_size: usize,
}

impl IoService {
    pub fn open(path: impl AsRef<Path>, block_size: usize) -> Result<Self, IoError> {
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .open(path)?;
        Ok(IoService { file, block_size})
    }
    // Read a block of data from the file. 
    pub fn read_block(&mut self, block_id: usize) -> Result<Vec<u8>, IoError>{
        let offset = block_id * self.block_size;
        self.file.seek(SeekFrom::Start(offset as u64))?;
        let mut buffer = vec![0; self.block_size];
        self.file.read_exact(&mut buffer)?;
        Ok(buffer)
    }

    // write a block of data to the file. 
    pub fn write_block(&mut self, block_id: usize, data: &[u8]) -> Result<(), IoError> {
        if data.len() != self.block_size {
            return Err(IoError::BlockOutOfBounds); 
        }        

        let offset = block_id * self.block_size;
        self.file.seek(SeekFrom::Start(offset as u64))?;
        self.file.write_all(data)?;
        Ok(())

    }

    // append a new block to the file
    pub fn append_block(&mut self, data: &[u8]) -> Result<usize, IoError> {
        if data.len() != self.block_size {
            return Err(IoError::BlockOutOfBounds); 
        }

        //seek to the end of file
        let file_size = self.file.seek(SeekFrom::End(0))?;
        println!("file_size = {}",file_size);
        //println!("file_size = {}",file_size);
        let block_id = (file_size as usize ) / self.block_size; 

        self.file.write_all(data)?;
        Ok(block_id)
    }

}