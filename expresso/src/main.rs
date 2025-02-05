use expresso::*;
fn main() -> Result<(), IoError> {
    let mut io_service = IoService::open("data.bin", 4096)?;

    let data = vec![1; 4096]; //4KB buffer
    io_service.write_block(0, &data)?;

    let read_data = io_service.read_block(0)?;
    assert_eq!(read_data, data); 

    //append a new block
    let new_block_id = io_service.append_block(&vec![2; 4096])?;
    println!("Appended block with ID: {}", new_block_id);

    Ok(())

}