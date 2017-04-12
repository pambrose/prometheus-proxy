import logging
import sys

from src.main.python.constants import GRPC_PORT_DEFAULT


def setup_logging(filename=None,
                  filemode="a",
                  stream=sys.stderr,
                  level=logging.INFO,
                  format="%(asctime)s %(name)-10s %(funcName)-10s():%(lineno)i: %(levelname)-6s %(message)s"):
    if filename:
        logging.basicConfig(filename=filename, filemode=filemode, level=level, format=format)
    else:
        logging.basicConfig(stream=stream, level=level, format=format)


def grpc_url(hostname):
    return hostname if ":" in hostname else hostname + ":{0}".format(GRPC_PORT_DEFAULT)

