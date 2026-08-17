import type { Config } from "genffi";

export default {
  outDir: "./src/main",
  verbose: true,

  //schema: { label: "Example JSON Schema" },

  java: {
    pkg: "de.ii.xtraplatform.xtralink.domain.jobs",
    api: {
      pkg: "de.ii.xtraplatform.xtralink.app.jobs",
    },
  },

  go: {
    path: "go",
    //pkgPrefix: "xtralink",
    module: "github.com/xtraplatform-native/xtralink",
    mod: {},
    api: {},
    cgo: {
      init: true,
    },
    impl: {},
    model: {},
  },
} satisfies Config;

export namespace GenModel {
  export namespace Enums {
    export enum Status {
      ACCEPTED = "ACCEPTED",
      RUNNING = "RUNNING",
      SUCCESSFUL = "SUCCESSFUL",
      FAILED = "FAILED",
      DISMISSED = "DISMISSED",
    }
  }

  export namespace Config {
    export type Job = {
      id: string;
      kind: string;
      createdAt: number;
      startedAt: number;
      updatedAt: number;
      finishedAt: number;
      status: Enums.Status;
      progress: number;
      errors: string[];
    };
    export type JobResult = {
      message: string;
      status: Enums.Status;
      errors: string[];
    };
  }
}

export namespace GenApi {

  export type int = number & { readonly __int: unique symbol };

  /**
   * @listener
   */
  export interface JobListener {
    onProgress: (job: GenModel.Config.Job) => void;
  }

  /**
   * @listener
   */
  export interface JobProcessor {
    /**
     * @throws
     */
    process: (job: GenModel.Config.Job) => GenModel.Config.JobResult;
  }

  /**
   * The one globally reachable object. `InitLibrary()` creates it by calling
   * `clib.NewInit()`, which you have to write by hand — it is the single obligation
   * genffi places on the implementation side.
   * @singleton
   */
  export interface JobQueue {
    create: (jobType: string) => GenModel.Config.Job;

    /**
     * The same thing through a `@scoped` listener, whose Java overload takes the interface
     * itself and manages the registration around the call. A single-method listener, so the
     * call site can be a lambda — which is the whole point of the tag and the one thing a
     * compile cannot prove is *usable*.
     * @scoped
     */
    push: (
      cfg: GenModel.Config.Job,
      onProgress: JobListener,
    ) => Promise<GenModel.Config.Job>;

    get: (id: string) => GenModel.Config.Job;
  }

  /**
   * The one globally reachable object. `InitLibrary()` creates it by calling
   * `clib.NewInit()`, which you have to write by hand — it is the single obligation
   * genffi places on the implementation side.
   * @singleton
   */
  export interface JobProcessors {
    register: (jobType: string, processor: JobProcessor) => boolean;
  }
}
