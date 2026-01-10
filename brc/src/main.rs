use std::{
    collections::{BTreeMap,HashMap},
    fs::File,
    os::fd::AsRawFd,
};

fn main() {
    let f = File::open("measurements.txt").unwrap();
    let map = mmap(&f);
    // initializing a hashmap
    let mut stats = HashMap::<Vec<u8>, (f64, f64, usize, f64)>::new();

    for line in map.split(|c| *c == b'\n') {
        if line.is_empty() {
            break;
        }
        let mut fields = line.rsplitn(2, |c| *c == b';');
        let (Some(temperature), Some(station)) = (fields.next(), fields.next()) else {
            panic!("bad line: {}", unsafe {
                std::str::from_utf8_unchecked(line)
            });
        };
        //utf8 unchecked is being used bcz, read me file specified that 
        // input file is utf-8 encoded. 
        let temperature: f64 = unsafe { std::str::from_utf8_unchecked(temperature) }
            .parse()
            .unwrap();        
        // removing the allocation of a new string
        let stats = match stats.get_mut(station) {
            Some(stats) => stats,
            None => stats
                .entry(station.to_vec())
                .or_insert((f64::MAX, 0., 0, f64::MIN)),
        };
        stats.0 = stats.0.min(temperature);
        stats.1 += temperature;
        stats.2 += 1;
        stats.3 = stats.3.max(temperature);
    }
    print!("{{");
    let stats = BTreeMap::from_iter(
        stats
            .into_iter()
            .map(|(k, v)| (unsafe {String::from_utf8_unchecked(k) }, v)),
    );
    let mut stats = stats.into_iter().peekable();
    while let Some((station, (min, sum, count, max))) = stats.next() {
        print!("{station}={min:.1}/{:.1}/{max:.1}", sum / (count as f64));
        if stats.peek().is_some() {
            print!(", ");
        }
    }
    print!("}}");
}
//Instead of reading file into a buffer, mmap tells the O.S to make file's bytes appear as if 
// they are already in memeory

fn mmap(f: &File) -> &'_ [u8] {
    let len = f.metadata().unwrap().len();
    unsafe {
        let ptr = libc::mmap(
            std::ptr::null_mut(),
            len as libc::size_t,
            libc::PROT_READ,
            libc::MAP_SHARED,
            f.as_raw_fd(),
            0,
        );
        // By calling madvice with MADV_SEQUENTIAL, i'm telling Kernel
        // I'm going to read the file from the beginning to end in a straight line.
        // Dont wait for me to hit a Page Fault- Start pre-loading the next chunks 
        // of data from the disk into RAM right now. 
        if ptr == libc::MAP_FAILED {
            panic!("{:?}", std::io::Error::last_os_error());
        } else {
            if libc::madvise(ptr, len as libc::size_t, libc::MADV_SEQUENTIAL) != 0 {
                panic!("{:?}", std::io::Error::last_os_error())
            }
            std::slice::from_raw_parts(ptr as *const u8, len as usize) 
        }

    }
}