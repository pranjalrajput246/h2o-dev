import sys
sys.path.insert(1, "../../")
import h2o

def https_import(ip,port):
    # Connect to h2o
    h2o.init(ip,port)

    url = "https://s3.amazonaws.com/h2o-public-test-data/smalldata/prostate/prostate.csv.zip"
    aa = h2o.import_frame(path=url)
    aa.show()

if __name__ == "__main__":
    h2o.run_test(sys.argv, https_import)
