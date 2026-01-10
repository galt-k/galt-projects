use std::{
    collections::{BTreeMap,HashMap},
    fs::File,
    os::fd::AsRawFd,
};

fn main() {
    let f = File::open("measurements.txt").unwrap();
    let map = mmap(&f);
    // initializing a hashmap
    let mut stats = HashMap::<Vec<u8>, (i16, i64, usize, i16)>::new();

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
        
        let t = parse_temperature(temperature);
        let stats = match stats.get_mut(station) {
            Some(stats) => stats,
            None => stats
                .entry(station.to_vec())
                .or_insert((i16::MAX, 0, 0, i16::MIN)),
        };
        stats.0 = stats.0.min(t);
        stats.1 += i64::from(t);
        stats.2 += 1;
        stats.3 = stats.3.max(t);
    }
    print!("{{");
    let stats = BTreeMap::from_iter(
        stats
            .into_iter()
            .map(|(k, v)| (unsafe {String::from_utf8_unchecked(k) }, v)),
    );
    let mut stats = stats.into_iter().peekable();
    while let Some((station, (min, sum, count, max))) = stats.next() {
        print!("{station}={:.1}/{:.1}/{:.1}", 
            (min as f64)/10.,
            (sum as f64)/10. / (count as f64),
            (max as f64)/10.
        );
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

fn parse_temperature(temperature: &[u8])-> i16 {
    let mut t : i16 = 0;
    let mut mul = 1;
    for &d in temperature.iter().rev() {
        match d {
            b'.' => {
                continue;
            }
            b'-' => {
                t = -t;
                break;
            }
            _ => {
                t += i16::from(d - b'0') * mul;
                mul *= 10;
            }
        }

    }
    t
}