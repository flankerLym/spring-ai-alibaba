import * as evaluators from './evaluators';
import * as prompt from './prompt';
import * as model from './model';

const API = {
  ...evaluators,
  ...prompt,
  ...model,
};

export default API;
