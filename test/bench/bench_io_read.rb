require 'benchmark'

MAX  = 10000
FILE = 'io_test_bench_file.txt'

File.open(FILE, 'w'){ |fh|
   1000.times{ |n|
      fh.puts "This is line: #{n}"
   }
}

(ARGV[0] || 5).to_i.times do
  Benchmark.bm(30) do |x|
     # Class Methods
     x.report('IO.read(file)'){
        MAX.times{ IO.read(FILE) }
     }

     x.report('IO.read(file, 100)'){
        MAX.times{ IO.read(FILE, 100) }
     }

     x.report('IO.read(file, 100, 20)'){
        MAX.times{ IO.read(FILE, 100, 20) }
     }
     
     x.report('IO.foreach(file)'){
        MAX.times{ IO.foreach(FILE){} }
     }
  end
end
File.delete(FILE) if File.exists?(FILE)
