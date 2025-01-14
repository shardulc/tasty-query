package tastyquery.reader.pickles

import PickleReader.{PklStream, index, pkl}

import tastyquery.Contexts.Context
import tastyquery.Exceptions.*
import tastyquery.Symbols.Symbol

private[reader] object Unpickler {
  def loadInfo(sigBytes: IArray[Byte])(using Context): Unit = {

    def run(reader: PickleReader, structure: reader.Structure)(using PklStream): Unit = {
      import structure.given
      index.loopWithIndices { (offset, i) =>
        if (reader.missingSymbolEntry(i)) {
          pkl.unsafeFork(offset) {
            reader.readMaybeExternalSymbolAt(i)
            //   sym.infoOrCompleter match {
            //     case info: ClassUnpickler => info.init()
            //     case _                    =>
            //   }
          }
        }
      }
      // read children last, fix for SI-3951
      index.loopWithIndices { (offset, i) =>
        if (reader.missingEntry(i)) {
          // if (isSymbolAnnotationEntry(i)) {
          //   data.unsafeFork(reader.index(i)) {
          //     readSymbolAnnotation()
          //   }
          // } else if (isChildrenEntry(i)) {
          //   data.unsafeFork(index(i)) {
          //     readChildren()
          //   }
          // }
        }
      }

      // Check that all the Symbols we created have been completed
      for sym <- structure.allRegisteredSymbols do sym.checkCompleted()
    }

    PklStream.read(sigBytes) {
      val reader = PickleReader()
      run(reader, reader.readStructure())
    }
  }

}
